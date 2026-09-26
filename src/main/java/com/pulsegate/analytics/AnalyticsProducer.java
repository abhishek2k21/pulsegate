package com.pulsegate.analytics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulsegate.config.KafkaConfig;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Async analytics event publisher.
 *
 * Design decisions:
 *   1. Fire-and-forget: publish() does not block the calling request thread.
 *   2. Best-effort: if Kafka is unavailable, the error is logged and the request
 *      continues normally — analytics loss is preferable to request failure.
 *   3. Partition by routeId: ensures all events for a route land on the same
 *      partition, enabling sequential processing and ordered aggregation.
 *   4. JSON serialization: human-readable, schema-flexible, easy to consume
 *      from any downstream (Spark, Flink, ClickHouse, Elasticsearch).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalyticsProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    /**
     * Publishes a request event to Kafka. Non-blocking — returns immediately.
     * Failures are logged but never propagated to the caller.
     */
    public void publish(RequestEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(KafkaConfig.REQUEST_EVENTS_TOPIC, event.partitionKey(), payload)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.warn("Analytics event publish failed (route={}, correlationId={}): {}",
                            event.getRouteId(), event.getCorrelationId(), ex.getMessage());
                        meterRegistry.counter("pulsegate.analytics.publish.errors").increment();
                    } else {
                        meterRegistry.counter("pulsegate.analytics.publish.success",
                            "route", event.getRouteId() != null ? event.getRouteId() : "unknown"
                        ).increment();
                    }
                });
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize RequestEvent for correlationId={}: {}",
                event.getCorrelationId(), e.getMessage());
        }
    }
}
