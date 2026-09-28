package com.pulsegate.gateway;

import com.pulsegate.gateway.filter.GatewayFilter;
import com.pulsegate.gateway.filter.GatewayFilterChain;
import com.pulsegate.gateway.proxy.ReactiveProxyHandler;
import com.pulsegate.gateway.router.RouteCache;
import com.pulsegate.model.Route;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GatewayRoutingWebFilterTest {

    @Mock private RouteCache routeCache;
    @Mock private ReactiveProxyHandler proxyHandler;
    @Mock private WebFilterChain defaultChain;

    private GatewayRoutingWebFilter routingWebFilter;

    @BeforeEach
    void setUp() {
        GatewayFilter dummyFilter = new GatewayFilter() {
            @Override public String name() { return "DummyFilter"; }
            @Override public int order() { return 1; }
            @Override
            public Mono<Void> filter(org.springframework.web.server.ServerWebExchange exchange, GatewayFilterChain chain) {
                return chain.filter(exchange);
            }
        };

        routingWebFilter = new GatewayRoutingWebFilter(
            routeCache, List.of(dummyFilter), proxyHandler
        );
    }

    @Test
    @DisplayName("Should pass internal /admin and /actuator endpoints to default WebFilterChain")
    void shouldPassInternalEndpointsThrough() {
        when(defaultChain.filter(any())).thenReturn(Mono.empty());

        MockServerWebExchange exchangeAdmin = MockServerWebExchange.from(
            MockServerHttpRequest.get("/admin/routes").build()
        );
        StepVerifier.create(routingWebFilter.filter(exchangeAdmin, defaultChain)).verifyComplete();

        MockServerWebExchange exchangeActuator = MockServerWebExchange.from(
            MockServerHttpRequest.get("/actuator/health").build()
        );
        StepVerifier.create(routingWebFilter.filter(exchangeActuator, defaultChain)).verifyComplete();

        verifyNoInteractions(routeCache);
        verify(defaultChain, times(2)).filter(any());
    }

    @Test
    @DisplayName("Should pass unmatched requests to default WebFilterChain")
    void shouldPassUnmatchedRequestsThrough() {
        when(routeCache.findMatchingRoute("/unmatched/path")).thenReturn(Optional.empty());
        when(defaultChain.filter(any())).thenReturn(Mono.empty());

        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/unmatched/path").build()
        );

        StepVerifier.create(routingWebFilter.filter(exchange, defaultChain)).verifyComplete();
        verify(defaultChain).filter(exchange);
        verifyNoInteractions(proxyHandler);
    }

    @Test
    @DisplayName("Should route matched request through filter pipeline to proxyHandler")
    void shouldRouteMatchedRequestToProxyHandler() {
        Route testRoute = Route.builder()
            .routeId("order-service")
            .pathPattern("/api/v1/orders/**")
            .upstreamUrl("http://order-service:8081")
            .build();

        when(routeCache.findMatchingRoute("/api/v1/orders/123")).thenReturn(Optional.of(testRoute));
        when(proxyHandler.proxy(any(), eq(testRoute))).thenReturn(Mono.empty());

        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/v1/orders/123").build()
        );

        StepVerifier.create(routingWebFilter.filter(exchange, defaultChain)).verifyComplete();

        verify(proxyHandler).proxy(any(), eq(testRoute));
        verifyNoInteractions(defaultChain);
    }
}
