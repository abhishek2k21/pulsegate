package com.pulsegate.gateway.filter;

import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Plugin interface for gateway filter pipeline.
 *
 * Filters are ordered by priority and applied in sequence per request.
 * Each filter calls chain.filter(exchange) to pass control to the next filter,
 * or short-circuits by returning a Mono without calling chain.filter()
 * (used for auth rejection, rate limit denial, circuit breaker open state).
 *
 * Design principle: each filter is independently configurable, testable,
 * and deployable. No filter has knowledge of other filters.
 */
public interface GatewayFilter {

    /**
     * @return Display name for logging and metrics
     */
    String name();

    /**
     * @return Lower number = higher priority (applied earlier in chain)
     */
    int order();

    /**
     * Apply this filter. Call chain.filter(exchange) to proceed,
     * or return early to short-circuit the pipeline.
     */
    Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain);
}
