package com.pulsegate.gateway.filter;

import com.pulsegate.gateway.router.RouteCache;
import com.pulsegate.model.Route;
import com.pulsegate.ratelimit.RateLimitResult;
import com.pulsegate.ratelimit.RateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Optional;

/**
 * Rate limiting filter — sits behind CorrelationIdFilter and AuthFilter in the pipeline.
 *
 * Key resolution order: API_KEY header → JWT user ID (from auth context) → client IP
 * Rate limit headers returned per IETF draft (RateLimit-Limit, RateLimit-Remaining, RateLimit-Reset).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitFilter implements GatewayFilter {

    private final Map<String, RateLimiter> rateLimiters;   // Spring injects all RateLimiter beans by name
    private final RouteCache routeCache;

    @Override public String name()  { return "RateLimitFilter"; }
    @Override public int    order() { return 30; }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        Optional<Route> routeOpt = routeCache.findMatchingRoute(path);

        if (routeOpt.isEmpty() || routeOpt.get().getRateLimitPolicyId() == null) {
            return chain.filter(exchange);   // No rate limit configured for this route
        }

        Route route = routeOpt.get();
        String key  = resolveKey(exchange);

        return resolveRateLimiter(route)
            .flatMap(limiter -> limiter.isAllowed(key, route.getRateLimitPolicyId()))
            .flatMap(result -> {
                addRateLimitHeaders(exchange, result);
                if (!result.isAllowed()) {
                    log.warn("Rate limit exceeded: key={}, route={}", key, route.getRouteId());
                    exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                    return exchange.getResponse().setComplete();
                }
                return chain.filter(exchange);
            });
    }

    private Mono<RateLimiter> resolveRateLimiter(Route route) {
        // TODO: load algorithm from policy; for now default to token bucket
        String beanName = "tokenBucketRateLimiter";
        RateLimiter limiter = rateLimiters.get(beanName);
        return limiter != null ? Mono.just(limiter)
            : Mono.error(new IllegalStateException("No rate limiter bean: " + beanName));
    }

    private String resolveKey(ServerWebExchange exchange) {
        // Priority: API-Key header → X-Forwarded-For → remote address
        String apiKey = exchange.getRequest().getHeaders().getFirst("X-API-Key");
        if (apiKey != null && !apiKey.isBlank()) return "apikey:" + apiKey;

        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null) return "ip:" + forwarded.split(",")[0].trim();

        return Optional.ofNullable(exchange.getRequest().getRemoteAddress())
            .map(InetSocketAddress::getHostString)
            .map(ip -> "ip:" + ip)
            .orElse("ip:unknown");
    }

    private void addRateLimitHeaders(ServerWebExchange exchange, RateLimitResult result) {
        var headers = exchange.getResponse().getHeaders();
        headers.add("X-RateLimit-Limit",     String.valueOf(result.getLimit()));
        headers.add("X-RateLimit-Remaining", String.valueOf(result.getRemainingRequests()));
        headers.add("X-RateLimit-Reset",     String.valueOf(result.getResetEpochMs() / 1000));
        headers.add("X-RateLimit-Algorithm", result.getAlgorithm());
    }
}
