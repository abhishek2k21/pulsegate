package com.pulsegate.circuitbreaker;

import com.pulsegate.model.CircuitBreakerConfig;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for all circuit breaker instances.
 * Creates circuit breakers on-demand and maintains them in a thread-safe map.
 * Exposes a shared Flux for WebSocket clients to subscribe to state events.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CircuitBreakerRegistry {

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final MeterRegistry meterRegistry;

    private final ConcurrentHashMap<String, CircuitBreaker> registry = new ConcurrentHashMap<>();

    /**
     * Hot-replay sink: new WebSocket subscribers immediately receive the last event
     * (so they see current state without waiting for next transition).
     */
    private final Sinks.Many<CircuitBreakerStateEvent> globalSink =
        Sinks.many().multicast().onBackpressureBuffer(256);

    /**
     * Returns or creates a circuit breaker for the given route.
     */
    public CircuitBreaker getOrCreate(String name, CircuitBreakerConfig config) {
        return registry.computeIfAbsent(name, key -> {
            log.info("Creating circuit breaker: {}", key);
            return new CircuitBreaker(key, config, redisTemplate, meterRegistry, globalSink);
        });
    }

    public CircuitBreaker get(String name) {
        return registry.get(name);
    }

    /**
     * Flux of all state transition events — subscribe for WebSocket push.
     */
    public Flux<CircuitBreakerStateEvent> stateEvents() {
        return globalSink.asFlux();
    }

    public java.util.Map<String, CircuitBreakerState> getAllStates() {
        var states = new java.util.HashMap<String, CircuitBreakerState>();
        registry.forEach((name, cb) -> states.put(name, cb.getState().get()));
        return states;
    }
}
