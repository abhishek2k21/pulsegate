package com.pulsegate.gateway.filter;

import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Functional interface for the gateway filter chain.
 * Implementations hold a reference to the remaining filters in the pipeline.
 */
@FunctionalInterface
public interface GatewayFilterChain {
    Mono<Void> filter(ServerWebExchange exchange);
}
