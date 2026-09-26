package com.pulsegate.gateway;

import com.pulsegate.gateway.filter.CorrelationIdFilter;
import com.pulsegate.gateway.filter.GatewayFilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.reactive.function.server.MockServerRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the filter pipeline components.
 * Uses Spring's MockServerWebExchange to simulate requests without a running server.
 */
class FilterChainTest {

    private final CorrelationIdFilter correlationIdFilter = new CorrelationIdFilter();

    @Test
    @DisplayName("CorrelationIdFilter: should generate X-Correlation-ID when absent")
    void correlationIdFilter_shouldGenerateIdWhenAbsent() {
        MockServerHttpRequest request = MockServerHttpRequest
            .get("/test/path").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        // Chain that just records the correlation ID after the filter runs
        String[] capturedId = {null};
        GatewayFilterChain chain = ex -> {
            capturedId[0] = ex.getRequest().getHeaders()
                .getFirst(CorrelationIdFilter.CORRELATION_ID_HEADER);
            return Mono.empty();
        };

        StepVerifier.create(correlationIdFilter.filter(exchange, chain))
            .verifyComplete();

        assertThat(capturedId[0]).isNotNull().isNotBlank();
        // Should be a valid UUID format
        assertThat(capturedId[0]).matches(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        );
    }

    @Test
    @DisplayName("CorrelationIdFilter: should preserve existing X-Correlation-ID")
    void correlationIdFilter_shouldPreserveExistingId() {
        String existingId = "existing-correlation-id-12345";
        MockServerHttpRequest request = MockServerHttpRequest
            .get("/test/path")
            .header(CorrelationIdFilter.CORRELATION_ID_HEADER, existingId)
            .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        String[] capturedId = {null};
        GatewayFilterChain chain = ex -> {
            capturedId[0] = ex.getRequest().getHeaders()
                .getFirst(CorrelationIdFilter.CORRELATION_ID_HEADER);
            return Mono.empty();
        };

        StepVerifier.create(correlationIdFilter.filter(exchange, chain))
            .verifyComplete();

        assertThat(capturedId[0]).isEqualTo(existingId);
    }

    @Test
    @DisplayName("CorrelationIdFilter: should add order=10")
    void correlationIdFilter_shouldHaveCorrectOrder() {
        assertThat(correlationIdFilter.order()).isEqualTo(10);
    }

    @Test
    @DisplayName("CorrelationIdFilter: should have correct name")
    void correlationIdFilter_shouldHaveCorrectName() {
        assertThat(correlationIdFilter.name()).isEqualTo("CorrelationIdFilter");
    }
}
