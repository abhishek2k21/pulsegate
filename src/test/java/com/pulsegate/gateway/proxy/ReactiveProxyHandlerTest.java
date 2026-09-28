package com.pulsegate.gateway.proxy;

import com.pulsegate.model.Route;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReactiveProxyHandlerTest {

    private ReactiveProxyHandler proxyHandler;

    @BeforeEach
    void setUp() {
        proxyHandler = new ReactiveProxyHandler();
    }

    @Test
    @DisplayName("Should build target URI without prefix stripping when stripPrefix is 0")
    void shouldBuildTargetUriWithoutStripping() {
        Route route = Route.builder()
            .upstreamUrl("http://order-service:8081")
            .stripPrefix(0)
            .build();

        MockServerHttpRequest request = MockServerHttpRequest
            .get("/api/v1/orders/123")
            .build();

        URI targetUri = proxyHandler.buildTargetUri(request, route);
        assertThat(targetUri.toString()).isEqualTo("http://order-service:8081/api/v1/orders/123");
    }

    @Test
    @DisplayName("Should strip single prefix segment when stripPrefix is 1")
    void shouldStripSinglePrefixSegment() {
        Route route = Route.builder()
            .upstreamUrl("http://order-service:8081")
            .stripPrefix(1)
            .build();

        MockServerHttpRequest request = MockServerHttpRequest
            .get("/api/orders/456")
            .build();

        URI targetUri = proxyHandler.buildTargetUri(request, route);
        assertThat(targetUri.toString()).isEqualTo("http://order-service:8081/orders/456");
    }

    @Test
    @DisplayName("Should strip multiple prefix segments and normalize trailing slashes on upstreamUrl")
    void shouldStripMultiplePrefixAndNormalizeTrailingSlash() {
        Route route = Route.builder()
            .upstreamUrl("http://order-service:8081/")
            .stripPrefix(2)
            .build();

        MockServerHttpRequest request = MockServerHttpRequest
            .get("/api/v1/orders/item?category=books&sort=asc")
            .build();

        URI targetUri = proxyHandler.buildTargetUri(request, route);
        assertThat(targetUri.toString()).isEqualTo("http://order-service:8081/orders/item?category=books&sort=asc");
    }

    @Test
    @DisplayName("Should default to root path when stripPrefix count exceeds available segments")
    void shouldDefaultToRootWhenStripPrefixExceedsSegments() {
        Route route = Route.builder()
            .upstreamUrl("http://order-service:8081")
            .stripPrefix(5)
            .build();

        MockServerHttpRequest request = MockServerHttpRequest
            .get("/api/test")
            .build();

        URI targetUri = proxyHandler.buildTargetUri(request, route);
        assertThat(targetUri.toString()).isEqualTo("http://order-service:8081/");
    }

    @Test
    @DisplayName("Should copy request headers while stripping Host and Content-Length")
    void shouldCopyHeadersExceptHostAndContentLength() {
        HttpHeaders source = new HttpHeaders();
        source.set("Host", "gateway.example.com");
        source.set("Content-Length", "1024");
        source.set("X-Correlation-ID", "corr-12345");
        source.set("Authorization", "Bearer eyJhbGciOi...");
        source.put("Accept", List.of("application/json", "text/plain"));

        HttpHeaders target = new HttpHeaders();
        proxyHandler.copyHeaders(source, target);

        assertThat(target.containsKey("Host")).isFalse();
        assertThat(target.containsKey("Content-Length")).isFalse();
        assertThat(target.getFirst("X-Correlation-ID")).isEqualTo("corr-12345");
        assertThat(target.getFirst("Authorization")).isEqualTo("Bearer eyJhbGciOi...");
        assertThat(target.get("Accept")).containsExactly("application/json", "text/plain");
    }
}
