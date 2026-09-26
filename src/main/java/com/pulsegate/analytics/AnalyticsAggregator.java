package com.pulsegate.analytics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulsegate.config.KafkaConfig;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Kafka consumer that aggregates request events into in-memory metrics.
 *
 * Aggregations maintained (rolling 5-minute window):
 *   - Total requests per route
 *   - Error count per route (5xx)
 *   - Rate-limited request count per route
 *   - Total duration sum per route (for avg latency calculation)
 *
 * These aggregates are exposed via the admin analytics API (/admin/analytics)
 * and also exported to Prometheus via MeterRegistry for Grafana dashboards.
 *
 * In production, you'd replace this with a proper streaming system
 * (Kafka Streams, Flink, or ClickHouse ingestion) — but this approach
 * is perfect for a single-node deployment and demo purposes.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalyticsAggregator {

    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    // Route-level aggregate counters — thread-safe for concurrent Kafka consumer threads
    private final Map<String, AtomicLong> requestCounts    = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> errorCounts      = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> rateLimitCounts  = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> durationSums     = new ConcurrentHashMap<>();

    @KafkaListener(
        topics    = KafkaConfig.REQUEST_EVENTS_TOPIC,
        groupId   = "pulsegate-analytics-aggregator",
        concurrency = "3"   // 3 consumer threads — one per Kafka partition
    )
    public void consume(String payload) {
        try {
            RequestEvent event = objectMapper.readValue(payload, RequestEvent.class);
            String route = event.getRouteId() != null ? event.getRouteId() : "unknown";

            // Aggregate counters
            requestCounts.computeIfAbsent(route, k -> new AtomicLong(0)).incrementAndGet();
            durationSums.computeIfAbsent(route, k -> new AtomicLong(0)).addAndGet(event.getDurationMs());

            if (event.isError()) {
                errorCounts.computeIfAbsent(route, k -> new AtomicLong(0)).incrementAndGet();
            }
            if (event.isRateLimited()) {
                rateLimitCounts.computeIfAbsent(route, k -> new AtomicLong(0)).incrementAndGet();
            }

            // Publish to Prometheus for Grafana
            meterRegistry.counter("pulsegate.requests.total",
                "route", route, "status", String.valueOf(event.getStatusCode())
            ).increment();

            meterRegistry.timer("pulsegate.request.duration",
                "route", route
            ).record(java.time.Duration.ofMillis(event.getDurationMs()));

        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize analytics event: {}", e.getMessage());
        }
    }

    /** Returns a snapshot of current aggregates for the admin API. */
    public Map<String, RouteStats> getSnapshot() {
        Map<String, RouteStats> snapshot = new ConcurrentHashMap<>();
        requestCounts.forEach((route, count) -> {
            long total    = count.get();
            long errors   = errorCounts.getOrDefault(route, new AtomicLong(0)).get();
            long limited  = rateLimitCounts.getOrDefault(route, new AtomicLong(0)).get();
            long durSum   = durationSums.getOrDefault(route, new AtomicLong(0)).get();
            double avgMs  = total > 0 ? (double) durSum / total : 0.0;
            double errRate = total > 0 ? (double) errors / total * 100.0 : 0.0;

            snapshot.put(route, RouteStats.builder()
                .routeId(route)
                .totalRequests(total)
                .errorCount(errors)
                .rateLimitedCount(limited)
                .avgLatencyMs(avgMs)
                .errorRatePercent(errRate)
                .build());
        });
        return snapshot;
    }

    @lombok.Builder
    @lombok.Data
    public static class RouteStats {
        private String routeId;
        private long   totalRequests;
        private long   errorCount;
        private long   rateLimitedCount;
        private double avgLatencyMs;
        private double errorRatePercent;
    }
}
