package com.pulsegate.gateway.proxy;

import com.pulsegate.model.Route;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyExtractors;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;

/**
 * High-performance non-blocking reverse proxy handler.
 * Forwards matching client requests from PulseGate to the target upstream microservice
 * using Spring WebFlux non-blocking WebClient, streaming response bodies back to the caller.
 */
@Slf4j
@Component
public class ReactiveProxyHandler {

    private static final Duration DEFAULT_UPSTREAM_TIMEOUT = Duration.ofSeconds(5);
    private final WebClient webClient;

    public ReactiveProxyHandler() {
        this.webClient = WebClient.builder()
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
            .build();
    }

    /**
     * Proxies the exchange to the target route upstream URL.
     */
    public Mono<Void> proxy(ServerWebExchange exchange, Route route) {
        ServerHttpRequest request = exchange.getRequest();
        ServerHttpResponse response = exchange.getResponse();

        URI targetUri = buildTargetUri(request, route);
        HttpMethod method = request.getMethod();

        log.debug("Proxying {} {} -> {}", method, request.getPath(), targetUri);

        WebClient.RequestBodySpec requestSpec = webClient
            .method(method)
            .uri(targetUri)
            .headers(headers -> copyHeaders(request.getHeaders(), headers));

        Mono<ClientResponse> responseMono;
        if (hasRequestBody(method)) {
            responseMono = requestSpec
                .body(BodyInserters.fromDataBuffers(request.getBody()))
                .exchange();
        } else {
            responseMono = requestSpec.exchange();
        }

        responseMono = responseMono.timeout(DEFAULT_UPSTREAM_TIMEOUT);

        return responseMono.flatMap(clientResponse -> {
            response.setStatusCode(clientResponse.statusCode());
            clientResponse.headers().asHttpHeaders().forEach((headerName, headerValues) -> {
                if (!HttpHeaders.TRANSFER_ENCODING.equalsIgnoreCase(headerName)) {
                    response.getHeaders().addAll(headerName, headerValues);
                }
            });

            return response.writeWith(clientResponse.body(BodyExtractors.toDataBuffers()));
        });
    }

    private URI buildTargetUri(ServerHttpRequest request, Route route) {
        String path = request.getPath().value();
        int stripPrefixCount = route.getStripPrefix();
        if (stripPrefixCount > 0) {
            String[] segments = path.split("/");
            int startIndex = 1 + stripPrefixCount;
            if (startIndex < segments.length) {
                StringBuilder sb = new StringBuilder();
                for (int i = startIndex; i < segments.length; i++) {
                    sb.append("/").append(segments[i]);
                }
                path = sb.toString();
            } else {
                path = "/";
            }
        }

        String query = request.getURI().getRawQuery();
        String uriString = route.getUpstreamUrl() + path + (query != null ? "?" + query : "");
        return URI.create(uriString);
    }

    private void copyHeaders(HttpHeaders source, HttpHeaders target) {
        source.forEach((name, values) -> {
            if (!HttpHeaders.HOST.equalsIgnoreCase(name) && !HttpHeaders.CONTENT_LENGTH.equalsIgnoreCase(name)) {
                target.addAll(name, values);
            }
        });
    }

    private boolean hasRequestBody(HttpMethod method) {
        return method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH;
    }
}
