package com.pulsegate.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulsegate.circuitbreaker.CircuitBreakerRegistry;
import com.pulsegate.circuitbreaker.CircuitBreakerStateEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

/**
 * WebSocket handler for real-time circuit breaker state dashboard.
 *
 * Endpoint: ws://gateway/ws/circuit-breakers
 *
 * On connection:
 *   1. Immediately sends current state snapshot of all circuit breakers.
 *   2. Subscribes to the global state event Flux.
 *   3. Pushes each transition event as JSON to the client.
 *
 * This enables the admin dashboard to show live state flips without polling.
 *
 * Backpressure: uses onBackpressureLatest to drop intermediate events
 * if the WebSocket client is slow — avoids memory accumulation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CircuitBreakerLiveHandler implements WebSocketHandler {

    private final CircuitBreakerRegistry cbRegistry;
    private final ObjectMapper objectMapper;

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        log.info("WebSocket circuit-breaker client connected: {}", session.getId());

        // Initial snapshot — send current state of all circuit breakers
        Mono<WebSocketMessage> snapshotMsg = Mono.fromCallable(() -> {
            var snapshot = cbRegistry.getAllStates();
            return session.textMessage(objectMapper.writeValueAsString(
                java.util.Map.of("type", "SNAPSHOT", "states", snapshot)
            ));
        });

        // Stream of live state transition events
        var eventStream = cbRegistry.stateEvents()
            .onBackpressureLatest()
            .map(event -> session.textMessage(serializeEvent(event)))
            .doOnNext(msg -> log.debug("CB WebSocket push: {}", msg.getPayloadAsText()));

        // Concatenate: snapshot first, then live stream
        return session.send(
            snapshotMsg.flux().concatWith(eventStream)
        ).doFinally(signal -> log.info("WebSocket circuit-breaker client disconnected: {}", session.getId()));
    }

    private String serializeEvent(CircuitBreakerStateEvent event) {
        try {
            return objectMapper.writeValueAsString(java.util.Map.of(
                "type",      "TRANSITION",
                "name",       event.circuitBreakerName(),
                "from",       event.previousState().name(),
                "to",         event.newState().name(),
                "timestamp",  event.occurredAt().toEpochMilli()
            ));
        } catch (Exception e) {
            return "{\"type\":\"ERROR\"}";
        }
    }
}
