package com.pulsegate.gateway;

import com.pulsegate.gateway.filter.GatewayFilter;
import com.pulsegate.gateway.filter.GatewayFilterChain;
import com.pulsegate.gateway.proxy.ReactiveProxyHandler;
import com.pulsegate.gateway.router.RouteCache;
import com.pulsegate.model.Route;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Primary WebFlux entry point for PulseGate API Gateway.
 * Intercepts incoming HTTP requests, matches them against cached routes in RouteCache,
 * executes the configured GatewayFilter pipeline, and proxies the request to the upstream service.
 */
@Slf4j
@Component
public class GatewayRoutingWebFilter implements WebFilter, Ordered {

    private final RouteCache routeCache;
    private final List<GatewayFilter> filters;
    private final ReactiveProxyHandler proxyHandler;

    public GatewayRoutingWebFilter(
            RouteCache routeCache,
            List<GatewayFilter> filters,
            ReactiveProxyHandler proxyHandler) {
        this.routeCache = routeCache;
        this.filters = filters.stream()
            .sorted(Comparator.comparingInt(GatewayFilter::order))
            .toList();
        this.proxyHandler = proxyHandler;
        log.info("Initialized GatewayRoutingWebFilter with {} filters: {}",
            this.filters.size(), this.filters.stream().map(GatewayFilter::name).toList());
    }

    @Override
    public int getOrder() {
        return 0; // After Spring Security (-100), before DispatcherHandler static resources
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        // Pass internal infrastructure endpoints directly to Spring handlers
        if (path.startsWith("/admin") || path.startsWith("/actuator") ||
            path.startsWith("/ws") || path.startsWith("/swagger-ui") ||
            path.startsWith("/v3/api-docs") || path.startsWith("/webjars")) {
            return chain.filter(exchange);
        }

        Optional<Route> matchingRoute = routeCache.findMatchingRoute(path);
        if (matchingRoute.isEmpty()) {
            log.debug("No matching route found for path: {}", path);
            return chain.filter(exchange);
        }

        Route route = matchingRoute.get();
        log.debug("Routing request {} {} to upstream {}",
            exchange.getRequest().getMethod(), path, route.getUpstreamUrl());

        GatewayFilterChain pipeline = buildPipeline(route);
        return pipeline.filter(exchange);
    }

    private GatewayFilterChain buildPipeline(Route route) {
        GatewayFilterChain current = ex -> proxyHandler.proxy(ex, route);
        for (int i = filters.size() - 1; i >= 0; i--) {
            GatewayFilter filter = filters.get(i);
            GatewayFilterChain next = current;
            current = ex -> filter.filter(ex, next);
        }
        return current;
    }
}
