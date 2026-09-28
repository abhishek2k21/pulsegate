package com.pulsegate.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler exceptionHandler;

    @BeforeEach
    void setUp() {
        exceptionHandler = new GlobalExceptionHandler();
    }

    @Test
    @DisplayName("Should return 400 Bad Request for IllegalArgumentException")
    void shouldReturn400ForIllegalArgument() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/admin/routes").build()
        );

        ResponseEntity<Map<String, Object>> response = exceptionHandler.handleIllegalArgument(
            new IllegalArgumentException("Route ID already exists: test-route"), exchange
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("status")).isEqualTo(400);
        assertThat(response.getBody().get("error")).isEqualTo("Bad Request");
        assertThat(response.getBody().get("message")).isEqualTo("Route ID already exists: test-route");
        assertThat(response.getBody().get("path")).isEqualTo("/admin/routes");
    }

    @Test
    @DisplayName("Should return 404 Not Found for RouteNotFoundException")
    void shouldReturn404ForRouteNotFound() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/admin/routes/missing-id").build()
        );

        ResponseEntity<Map<String, Object>> response = exceptionHandler.handleRouteNotFound(
            new RouteNotFoundException("missing-id"), exchange
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("status")).isEqualTo(404);
        assertThat(response.getBody().get("error")).isEqualTo("Not Found");
        assertThat(response.getBody().get("message")).isEqualTo("Route not found: missing-id");
        assertThat(response.getBody().get("path")).isEqualTo("/admin/routes/missing-id");
    }

    @Test
    @DisplayName("Should return 400 Bad Request for ServerWebInputException")
    void shouldReturn400ForWebInputException() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/admin/routes").build()
        );

        ResponseEntity<Map<String, Object>> response = exceptionHandler.handleWebInputException(
            new org.springframework.web.server.ServerWebInputException("Failed to read HTTP message"), exchange
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("status")).isEqualTo(400);
        assertThat(response.getBody().get("error")).isEqualTo("Bad Request");
    }

    @Test
    @DisplayName("Should return 500 Internal Server Error for unhandled generic Exception")
    void shouldReturn500ForGenericException() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/admin/routes").build()
        );

        ResponseEntity<Map<String, Object>> response = exceptionHandler.handleGenericException(
            new RuntimeException("Database timeout"), exchange
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("status")).isEqualTo(500);
        assertThat(response.getBody().get("error")).isEqualTo("Internal Server Error");
        assertThat(response.getBody().get("message")).isEqualTo("An unexpected error occurred");
    }
}
