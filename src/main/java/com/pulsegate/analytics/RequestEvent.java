package com.pulsegate.analytics;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * Immutable record of a single gateway request — published to Kafka for async processing.
 *
 * Publishing analytics asynchronously (via Kafka) rather than synchronously keeps
 * the request latency overhead of analytics at near-zero: Kafka producer.send()
 * is fire-and-forget from the request thread's perspective.
 *
 * Downstream consumers can aggregate this into:
 *   - Per-route request rates and error rates (5xx ratio)
 *   - p50/p95/p99 latency histograms per route
 *   - Top clients by request volume
 *   - Circuit breaker trigger correlation with upstream error spikes
 */
@Data
@Builder
public class RequestEvent {

    private String  correlationId;
    private String  routeId;
    private String  method;
    private String  path;
    private String  clientKey;       // IP or user ID used for rate limiting
    private int     statusCode;
    private long    durationMs;
    private boolean rateLimited;
    private boolean circuitBreakerOpen;
    private String  upstreamUrl;
    private Instant timestamp;

    /** Partition key for Kafka — group events by routeId for locality-aware consumers. */
    public String partitionKey() {
        return routeId != null ? routeId : "unknown";
    }

    public boolean isError() {
        return statusCode >= 500;
    }

    public boolean isClientError() {
        return statusCode >= 400 && statusCode < 500;
    }
}
