package com.pulsegate.circuitbreaker;

import com.pulsegate.model.CircuitBreakerConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * State machine unit tests for the CircuitBreaker.
 * Verifies all state transitions: CLOSED→OPEN, OPEN→HALF_OPEN, HALF_OPEN→CLOSED, HALF_OPEN→OPEN.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CircuitBreakerStateTransitionTest {

    @Mock private ReactiveRedisTemplate<String, String> redisTemplate;
    @Mock private ReactiveValueOperations<String, String> valueOps;

    private CircuitBreaker circuitBreaker;
    private CircuitBreakerConfig config;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.set(anyString(), anyString(), any())).thenReturn(Mono.just(true));

        config = CircuitBreakerConfig.builder()
            .id(1L)
            .configName("test-cb")
            .failureRateThreshold(50.0f)
            .slowCallRateThreshold(80.0f)
            .slowCallDurationMs(1000L)
            .slidingWindowSize(10)
            .minimumNumberOfCalls(5)
            .waitDurationSeconds(30)
            .permittedCallsInHalfOpen(3)
            .build();

        Sinks.Many<CircuitBreakerStateEvent> sink = Sinks.many().multicast().onBackpressureBuffer();
        circuitBreaker = new CircuitBreaker("test-cb", config, redisTemplate, new SimpleMeterRegistry(), sink);
    }

    @Test
    @DisplayName("CLOSED: should allow calls when failure rate is below threshold")
    void closedState_allowsCallsBelowThreshold() {
        // Record 4 failures out of 5 = 80% — threshold is 50%, should open
        // But minimum_calls is 5, so after exactly 5 calls we check
        // First 4 calls: 2 success, 2 failure (40% rate — stays closed)
        circuitBreaker.recordResult(true, 50);
        circuitBreaker.recordResult(true, 50);
        circuitBreaker.recordResult(false, 50);
        circuitBreaker.recordResult(false, 50);
        circuitBreaker.recordResult(true, 50);  // 5th call, rate = 40% → CLOSED

        assertThat(circuitBreaker.getState().get()).isEqualTo(CircuitBreakerState.CLOSED);
        assertThat(circuitBreaker.isCallPermitted()).isTrue();
    }

    @Test
    @DisplayName("CLOSED → OPEN: should open when failure rate exceeds threshold")
    void closedToOpen_whenFailureRateExceedsThreshold() {
        // 6 failures out of 10 = 60% > 50% threshold → should OPEN
        for (int i = 0; i < 4; i++) circuitBreaker.recordResult(true, 100);
        for (int i = 0; i < 6; i++) circuitBreaker.recordResult(false, 100);

        assertThat(circuitBreaker.getState().get()).isEqualTo(CircuitBreakerState.OPEN);
        assertThat(circuitBreaker.isCallPermitted()).isFalse();
    }

    @Test
    @DisplayName("CLOSED → OPEN: should open when slow call rate exceeds threshold")
    void closedToOpen_whenSlowCallRateExceedsThreshold() {
        // All calls are slow (2000ms > slowCallDurationMs 1000ms) = 100% slow → should OPEN
        for (int i = 0; i < 10; i++) circuitBreaker.recordResult(true, 2000);

        assertThat(circuitBreaker.getState().get()).isEqualTo(CircuitBreakerState.OPEN);
    }

    @Test
    @DisplayName("OPEN: should reject calls immediately")
    void openState_rejectsImmediately() {
        openTheCircuit();

        assertThat(circuitBreaker.getState().get()).isEqualTo(CircuitBreakerState.OPEN);
        assertThat(circuitBreaker.isCallPermitted()).isFalse();
        assertThat(circuitBreaker.isCallPermitted()).isFalse();
    }

    @Test
    @DisplayName("HALF_OPEN → CLOSED: should close when probe requests succeed")
    void halfOpenToClosed_onSuccessfulProbes() {
        openTheCircuit();
        // Force HALF_OPEN by manually setting openedAt far in the past
        setOpenedAtPast();

        // First call should transition to HALF_OPEN and be permitted
        boolean permitted = circuitBreaker.isCallPermitted();
        assertThat(permitted).isTrue();
        assertThat(circuitBreaker.getState().get()).isEqualTo(CircuitBreakerState.HALF_OPEN);

        // Record all probe calls as successful → should CLOSE
        circuitBreaker.recordResult(true, 100);
        circuitBreaker.recordResult(true, 100);
        circuitBreaker.recordResult(true, 100);

        assertThat(circuitBreaker.getState().get()).isEqualTo(CircuitBreakerState.CLOSED);
    }

    @Test
    @DisplayName("HALF_OPEN → OPEN: should reopen when probe requests fail")
    void halfOpenToOpen_onFailedProbe() {
        openTheCircuit();
        setOpenedAtPast();

        circuitBreaker.isCallPermitted();   // Trigger HALF_OPEN
        assertThat(circuitBreaker.getState().get()).isEqualTo(CircuitBreakerState.HALF_OPEN);

        circuitBreaker.recordResult(false, 100);  // Single probe failure → reopen

        assertThat(circuitBreaker.getState().get()).isEqualTo(CircuitBreakerState.OPEN);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private void openTheCircuit() {
        for (int i = 0; i < 10; i++) circuitBreaker.recordResult(false, 100);
        assertThat(circuitBreaker.getState().get()).isEqualTo(CircuitBreakerState.OPEN);
    }

    private void setOpenedAtPast() {
        // Use reflection to set openedAt far in the past so shouldAttemptReset() returns true
        try {
            var field = CircuitBreaker.class.getDeclaredField("openedAt");
            field.setAccessible(true);
            field.set(circuitBreaker, java.time.Instant.now().minusSeconds(3600));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
