package com.pulsegate.admin;

import com.pulsegate.analytics.AnalyticsAggregator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Analytics dashboard API — exposes aggregated request metrics per route.
 * Data is aggregated by the Kafka consumer (AnalyticsAggregator) from
 * real request events, not estimated or mocked.
 */
@RestController
@RequestMapping("/admin/analytics")
@Tag(name = "Analytics", description = "Real-time request analytics aggregated from Kafka event stream")
@RequiredArgsConstructor
public class AnalyticsDashboardController {

    private final AnalyticsAggregator aggregator;

    @GetMapping
    @Operation(summary = "Get analytics snapshot for all routes",
               description = "Returns total requests, error rate, avg latency, and rate-limit counts per route")
    public Mono<Map<String, AnalyticsAggregator.RouteStats>> getAllRouteStats() {
        return Mono.fromSupplier(aggregator::getSnapshot);
    }

    @GetMapping("/{routeId}")
    @Operation(summary = "Get analytics for a specific route")
    public Mono<AnalyticsAggregator.RouteStats> getRouteStats(@PathVariable String routeId) {
        return Mono.fromSupplier(() -> {
            var snapshot = aggregator.getSnapshot();
            if (!snapshot.containsKey(routeId)) {
                throw new IllegalArgumentException("No analytics data for route: " + routeId);
            }
            return snapshot.get(routeId);
        });
    }
}
