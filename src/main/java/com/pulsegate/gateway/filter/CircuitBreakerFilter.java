package com.pulsegate.gateway.filter;

import com.pulsegate.circuitbreaker.CircuitBreaker;
import com.pulsegate.circuitbreaker.CircuitBreakerRegistry;
import com.pulsegate.gateway.router.RouteCache;
import com.pulsegate.model.Route;
import com.pulsegate.repository.RateLimitPolicyRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Optional;

/**
 * Circuit breaker filter — protects upstream services from cascading failures.
 *
 * When the circuit is OPEN, requests are immediately rejected with 503
 * (no upstream call is made). This protects a degraded upstream from
 * additional load while it recovers.
 *
 * Timing: wraps the downstream chain execution to measure actual call duration,
 * including upstream response time.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CircuitBreakerFilter implements GatewayFilter {

    private final CircuitBreakerRegistry cbRegistry;
    private final RouteCache routeCache;

    @Override public String name()  { return "CircuitBreakerFilter"; }
    @Override public int    order() { return 40; }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        Optional<Route> routeOpt = routeCache.findMatchingRoute(path);

        if (routeOpt.isEmpty() || routeOpt.get().getCircuitBreakerConfigId() == null) {
            return chain.filter(exchange);
        }

        Route route = routeOpt.get();
        String cbName = "cb:" + route.getRouteId();

        // Circuit breaker must be pre-registered (done by RouteCache on load)
        CircuitBreaker cb = cbRegistry.get(cbName);
        if (cb == null) {
            return chain.filter(exchange);
        }

        if (!cb.isCallPermitted()) {
            log.warn("Circuit OPEN — rejecting request for route: {}", route.getRouteId());
            exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            exchange.getResponse().getHeaders().add("X-Circuit-Breaker", "OPEN");
            exchange.getResponse().getHeaders().add("X-Circuit-Breaker-Name", cbName);
            return exchange.getResponse().setComplete();
        }

        long startMs = System.currentTimeMillis();

        return chain.filter(exchange)
            .doOnSuccess(v -> {
                long duration = System.currentTimeMillis() - startMs;
                int statusCode = exchange.getResponse().getStatusCode() != null
                    ? exchange.getResponse().getStatusCode().value() : 200;
                boolean success = statusCode < 500;
                cb.recordResult(success, duration);
            })
            .doOnError(ex -> {
                long duration = System.currentTimeMillis() - startMs;
                cb.recordResult(false, duration);
            });
    }
}
