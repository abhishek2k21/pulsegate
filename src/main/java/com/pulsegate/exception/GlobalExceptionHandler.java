package com.pulsegate.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebExchange;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Global exception handler providing RFC-7807 compliant and consistent error responses
 * across all PulseGate admin and gateway endpoints.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(
            IllegalArgumentException ex, ServerWebExchange exchange) {
        log.warn("Invalid client request: {}", ex.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage(), exchange);
    }

    @ExceptionHandler(RouteNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleRouteNotFound(
            RouteNotFoundException ex, ServerWebExchange exchange) {
        log.warn("Resource not found: {}", ex.getMessage());
        return buildResponse(HttpStatus.NOT_FOUND, ex.getMessage(), exchange);
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<Map<String, Object>> handleValidationException(
            WebExchangeBindException ex, ServerWebExchange exchange) {
        String errors = ex.getFieldErrors().stream()
            .map(err -> err.getField() + ": " + err.getDefaultMessage())
            .collect(Collectors.joining("; "));
        log.warn("Validation error on {}: {}", exchange.getRequest().getPath(), errors);
        return buildResponse(HttpStatus.BAD_REQUEST, errors, exchange);
    }

    @ExceptionHandler(org.springframework.web.server.ServerWebInputException.class)
    public ResponseEntity<Map<String, Object>> handleWebInputException(
            org.springframework.web.server.ServerWebInputException ex, ServerWebExchange exchange) {
        log.warn("Malformed or invalid request body on {}: {}", exchange.getRequest().getPath(), ex.getReason());
        return buildResponse(HttpStatus.BAD_REQUEST,
            ex.getReason() != null ? ex.getReason() : "Malformed request input", exchange);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(
            Exception ex, ServerWebExchange exchange) {
        log.error("Unhandled error processing request to {}: {}",
            exchange.getRequest().getPath(), ex.getMessage(), ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR,
            "An unexpected error occurred", exchange);
    }

    private ResponseEntity<Map<String, Object>> buildResponse(
            HttpStatus status, String message, ServerWebExchange exchange) {
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        body.put("path", exchange.getRequest().getPath().value());
        return ResponseEntity.status(status).body(body);
    }
}
