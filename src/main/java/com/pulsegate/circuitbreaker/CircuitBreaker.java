package com.pulsegate.circuitbreaker;

import com.pulsegate.model.CircuitBreakerConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Adaptive Circuit Breaker implementation with Redis-backed distributed state.
 *
 * This implementation uses a local sliding window (Deque) for failure rate
 * calculation and Redis for persisting the OPEN/HALF_OPEN state across
 * multiple gateway instances (distributed consensus).
 *
 * Key design decisions:
 *   1. Local window for low-latency failure rate calculation (no Redis per-request).
 *   2. Redis only for state transitions (infrequent, can tolerate ~1ms latency).
 *   3. Sinks.Many for WebSocket push — zero-copy fan-out to dashboard subscribers.
 *   4. AtomicReference for state — lock-free reads on the hot request path.
 */
@Slf4j
@Getter
public class CircuitBreaker {

    private static final String REDIS_CB_PREFIX = "cb:state:";

    private final String name;
    private final CircuitBreakerConfig config;
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final MeterRegistry meterRegistry;

    /** State sink for WebSocket live push — multicast to all dashboard subscribers. */
    private final Sinks.Many<CircuitBreakerStateEvent> stateSink;

    /** Lock-free state reference for hot-path reads. */
    private final AtomicReference<CircuitBreakerState> state =
        new AtomicReference<>(CircuitBreakerState.CLOSED);

    /** Sliding window of call outcomes: true=success, false=failure. */
    private final Deque<Boolean> callWindow = new ArrayDeque<>();

    /** Timestamp when the circuit entered OPEN state. */
    private volatile Instant openedAt;

    /** Count of probe calls allowed in HALF_OPEN state. */
    private final AtomicInteger halfOpenCallCount = new AtomicInteger(0);
    private final AtomicInteger halfOpenSuccessCount = new AtomicInteger(0);

    public CircuitBreaker(String name, CircuitBreakerConfig config,
                          ReactiveRedisTemplate<String, String> redisTemplate,
                          MeterRegistry meterRegistry,
                          Sinks.Many<CircuitBreakerStateEvent> stateSink) {
        this.name           = name;
        this.config         = config;
        this.redisTemplate  = redisTemplate;
        this.meterRegistry  = meterRegistry;
        this.stateSink      = stateSink;
        initMetrics();
    }

    /**
     * Determines whether to allow the current request through.
     * Hot path — must be non-blocking and sub-millisecond.
     */
    public boolean isCallPermitted() {
        CircuitBreakerState current = state.get();
        return switch (current) {
            case CLOSED -> true;
            case OPEN   -> {
                if (shouldAttemptReset()) {
                    transitionTo(CircuitBreakerState.HALF_OPEN);
                    yield halfOpenCallCount.getAndIncrement() < config.getPermittedCallsInHalfOpen();
                }
                yield false;
            }
            case HALF_OPEN -> halfOpenCallCount.getAndIncrement() < config.getPermittedCallsInHalfOpen();
        };
    }

    /**
     * Records the outcome of a call and triggers state transitions if thresholds are exceeded.
     */
    public synchronized void recordResult(boolean success, long durationMs) {
        boolean isSlow = durationMs > config.getSlowCallDurationMs();
        addToWindow(!success || isSlow);   // treat slow calls as failures for threshold calc

        recordCallMetric(success, isSlow, durationMs);

        if (state.get() == CircuitBreakerState.HALF_OPEN) {
            handleHalfOpenResult(success);
            return;
        }

        if (callWindow.size() >= config.getMinimumNumberOfCalls()) {
            float failureRate = calculateFailureRate();
            if (failureRate >= config.getFailureRateThreshold()) {
                transitionTo(CircuitBreakerState.OPEN);
            }
        }
    }

    private void handleHalfOpenResult(boolean success) {
        if (!success) {
            log.info("CircuitBreaker [{}] HALF_OPEN probe failed — reopening", name);
            transitionTo(CircuitBreakerState.OPEN);
        } else if (halfOpenSuccessCount.incrementAndGet() >= config.getPermittedCallsInHalfOpen()) {
            log.info("CircuitBreaker [{}] HALF_OPEN probes succeeded — closing", name);
            transitionTo(CircuitBreakerState.CLOSED);
        }
    }

    private boolean shouldAttemptReset() {
        return openedAt != null &&
            Instant.now().isAfter(openedAt.plusSeconds(config.getWaitDurationSeconds()));
    }

    private void addToWindow(boolean failure) {
        callWindow.addLast(failure);
        if (callWindow.size() > config.getSlidingWindowSize()) {
            callWindow.removeFirst();
        }
    }

    private float calculateFailureRate() {
        if (callWindow.isEmpty()) return 0f;
        long failures = callWindow.stream().filter(f -> f).count();
        return (float) failures / callWindow.size() * 100f;
    }

    private void transitionTo(CircuitBreakerState newState) {
        CircuitBreakerState oldState = state.getAndSet(newState);
        if (oldState == newState) return;

        log.info("CircuitBreaker [{}] transition: {} -> {}", name, oldState, newState);

        if (newState == CircuitBreakerState.OPEN) {
            openedAt = Instant.now();
        } else if (newState == CircuitBreakerState.CLOSED) {
            callWindow.clear();
            halfOpenCallCount.set(0);
            halfOpenSuccessCount.set(0);
        } else if (newState == CircuitBreakerState.HALF_OPEN) {
            halfOpenCallCount.set(0);
            halfOpenSuccessCount.set(0);
        }

        // Persist to Redis for multi-node awareness
        persistStateToRedis(newState).subscribe();

        // Push state change event to WebSocket subscribers
        CircuitBreakerStateEvent event = new CircuitBreakerStateEvent(name, oldState, newState, Instant.now());
        stateSink.tryEmitNext(event);

        meterRegistry.counter("pulsegate.circuitbreaker.transitions",
            "name", name, "from", oldState.name(), "to", newState.name()).increment();
    }

    private Mono<Boolean> persistStateToRedis(CircuitBreakerState newState) {
        String key = REDIS_CB_PREFIX + name;
        return redisTemplate.opsForValue()
            .set(key, newState.name(), Duration.ofSeconds(config.getWaitDurationSeconds() + 60))
            .doOnError(e -> log.warn("Failed to persist CB state to Redis: {}", e.getMessage()));
    }

    private void initMetrics() {
        meterRegistry.gauge("pulsegate.circuitbreaker.state", Tags.of("name", name), state,
            ref -> switch (ref.get()) { case CLOSED -> 0; case HALF_OPEN -> 1; case OPEN -> 2; });
    }

    private void recordCallMetric(boolean success, boolean slow, long durationMs) {
        meterRegistry.timer("pulsegate.circuitbreaker.call.duration",
            "name", name, "success", String.valueOf(success), "slow", String.valueOf(slow)
        ).record(Duration.ofMillis(durationMs));
    }
}
