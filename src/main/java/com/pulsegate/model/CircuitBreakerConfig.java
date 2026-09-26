package com.pulsegate.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Configuration for a circuit breaker instance bound to a gateway route.
 *
 * State transitions:
 *   CLOSED  → tracks failures; opens when failureRateThreshold exceeded
 *   OPEN    → rejects requests immediately; waits waitDurationSeconds before transitioning
 *   HALF_OPEN → allows limitForHalfOpen probe requests; closes if pass rate above threshold
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("circuit_breaker_configs")
public class CircuitBreakerConfig {

    @Id
    private Long id;

    @Column("config_name")
    private String configName;

    /** Percentage (0-100) of calls that must fail to open the circuit. */
    @Column("failure_rate_threshold")
    private float failureRateThreshold;

    /** Percentage (0-100) of calls that are slow (>slowCallDurationMs) to open the circuit. */
    @Column("slow_call_rate_threshold")
    private float slowCallRateThreshold;

    /** A call is considered slow if it takes longer than this threshold (ms). */
    @Column("slow_call_duration_ms")
    private long slowCallDurationMs;

    /** Number of calls in the sliding window used for failure rate calculation. */
    @Column("sliding_window_size")
    private int slidingWindowSize;

    /** Minimum number of calls before failure rate is calculated. */
    @Column("minimum_number_of_calls")
    private int minimumNumberOfCalls;

    /** How long (seconds) the circuit stays OPEN before transitioning to HALF_OPEN. */
    @Column("wait_duration_seconds")
    private int waitDurationSeconds;

    /** Number of probe requests allowed in HALF_OPEN state. */
    @Column("permitted_calls_in_half_open")
    private int permittedCallsInHalfOpen;
}
