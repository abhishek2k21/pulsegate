package com.pulsegate.circuitbreaker;

/**
 * States in the circuit breaker state machine.
 *
 * Transitions:
 *   CLOSED    → OPEN       (when failure/slow-call rate exceeds threshold)
 *   OPEN      → HALF_OPEN  (after waitDurationSeconds)
 *   HALF_OPEN → CLOSED     (when probe requests succeed above threshold)
 *   HALF_OPEN → OPEN       (when probe requests fail)
 */
public enum CircuitBreakerState {
    CLOSED,
    OPEN,
    HALF_OPEN
}
