package com.pulsegate.circuitbreaker;

import java.time.Instant;

/**
 * Immutable event published via Sinks.Many for WebSocket live dashboard push
 * whenever a circuit breaker state transition occurs.
 */
public record CircuitBreakerStateEvent(
    String circuitBreakerName,
    CircuitBreakerState previousState,
    CircuitBreakerState newState,
    Instant occurredAt
) {}
