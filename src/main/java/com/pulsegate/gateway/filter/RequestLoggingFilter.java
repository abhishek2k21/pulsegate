package com.pulsegate.gateway.filter;

import com.pulsegate.analytics.AnalyticsProducer;
import com.pulsegate.analytics.RequestEvent;
import com.pulsegate.gateway.router.RouteCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Optional;

/**
 * Terminal filter in the pipeline — captures request metadata and publishes
 * a RequestEvent to Kafka after the upstream response is received.
 *
 * Placed LAST in the pipeline (highest order number) so it measures the
 * total end-to-end duration including all other filter processing time
 * and upstream response time.
 *
 * Uses doFinally() instead of doOnSuccess/doOnError to guarantee the event
 * is published regardless of whether the chain succeeds or errors — including
 * cancelled subscriptions (client disconnects).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RequestLoggingFilter implements GatewayFilter {

    private final AnalyticsProducer analyticsProducer;
    private final RouteCache routeCache;

    @Override public String name()  { return "RequestLoggingFilter"; }
    @Override public int    order() { return Integer.MAX_VALUE - 1; }  // Always last

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long startMs       = System.currentTimeMillis();
        Instant requestAt  = Instant.now();
        String path        = exchange.getRequest().getPath().value();
        String method      = exchange.getRequest().getMethod().name();
        String clientKey   = resolveClientKey(exchange);
        String correlationId = exchange.getRequest().getHeaders()
            .getFirst(CorrelationIdFilter.CORRELATION_ID_HEADER);

        return chain.filter(exchange)
            .doFinally(signal -> {
                long durationMs = System.currentTimeMillis() - startMs;
                int statusCode  = exchange.getResponse().getStatusCode() != null
                    ? exchange.getResponse().getStatusCode().value() : 0;

                String routeId = routeCache.findMatchingRoute(path)
                    .map(r -> r.getRouteId())
                    .orElse("unmatched");

                boolean rateLimited     = statusCode == 429;
                boolean circuitOpen     = "OPEN".equals(
                    exchange.getResponse().getHeaders().getFirst("X-Circuit-Breaker"));

                RequestEvent event = RequestEvent.builder()
                    .correlationId(correlationId)
                    .routeId(routeId)
                    .method(method)
                    .path(path)
                    .clientKey(clientKey)
                    .statusCode(statusCode)
                    .durationMs(durationMs)
                    .rateLimited(rateLimited)
                    .circuitBreakerOpen(circuitOpen)
                    .timestamp(requestAt)
                    .build();

                // Structured log — compatible with ELK Stack (Logstash JSON parsing)
                log.info("[{}] {} {} → {} ({}ms) route={} rateLimited={} cbOpen={}",
                    correlationId, method, path, statusCode, durationMs,
                    routeId, rateLimited, circuitOpen);

                // Async Kafka publish — never blocks the response
                analyticsProducer.publish(event);
            });
    }

    private String resolveClientKey(ServerWebExchange exchange) {
        String apiKey = exchange.getRequest().getHeaders().getFirst("X-API-Key");
        if (apiKey != null) return "apikey:" + apiKey;

        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null) return "ip:" + forwarded.split(",")[0].trim();

        return Optional.ofNullable(exchange.getRequest().getRemoteAddress())
            .map(InetSocketAddress::getHostString)
            .map(ip -> "ip:" + ip)
            .orElse("ip:unknown");
    }
}
