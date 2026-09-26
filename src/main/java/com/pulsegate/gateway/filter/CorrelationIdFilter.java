package com.pulsegate.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Assigns a unique X-Correlation-ID to every request.
 * If the client supplies one, it is preserved (allows distributed tracing
 * from browser/mobile through the gateway to upstream services).
 * The ID is propagated to upstream requests and returned in the response.
 */
@Slf4j
@Component
public class CorrelationIdFilter implements GatewayFilter {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";

    @Override public String name()  { return "CorrelationIdFilter"; }
    @Override public int    order() { return 10; }   // First in pipeline

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String correlationId = exchange.getRequest().getHeaders()
            .getFirst(CORRELATION_ID_HEADER);

        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        final String finalId = correlationId;

        // Mutate request to add the header for upstream forwarding
        ServerWebExchange mutatedExchange = exchange.mutate()
            .request(r -> r.header(CORRELATION_ID_HEADER, finalId))
            .response(r -> r.getHeaders().add(CORRELATION_ID_HEADER, finalId))
            .build();

        return chain.filter(mutatedExchange)
            .contextWrite(ctx -> ctx.put("correlationId", finalId));
    }
}
