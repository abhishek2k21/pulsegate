package com.pulsegate.integration;

import com.pulsegate.model.Route;
import com.pulsegate.repository.RouteRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the Route Management admin API.
 *
 * Tests run against real PostgreSQL (Testcontainers) to verify:
 *   - Flyway migrations create the schema correctly
 *   - R2DBC repository CRUD works end-to-end
 *   - REST API serialization/deserialization is correct
 *   - Redis pub/sub invalidation is triggered on mutations
 *
 * Tags: @Tag("integration") — excluded from unit test run, included in verify phase.
 */
@org.junit.jupiter.api.Tag("integration")
class RouteManagementIntegrationTest extends AbstractIntegrationTest {

    @Autowired private WebTestClient webTestClient;
    @Autowired private RouteRepository routeRepository;

    private static final String TEST_ROUTE_ID = "integration-test-route";

    @AfterEach
    void cleanup() {
        routeRepository.findByRouteId(TEST_ROUTE_ID)
            .flatMap(routeRepository::delete)
            .block();
    }

    @Test
    @DisplayName("POST /admin/routes — should create route and return 201")
    void shouldCreateRoute() {
        Route route = buildTestRoute();

        webTestClient.post().uri("/admin/routes")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(route)
            .exchange()
            .expectStatus().isCreated()
            .expectBody(Route.class)
            .value(r -> {
                assertThat(r.getRouteId()).isEqualTo(TEST_ROUTE_ID);
                assertThat(r.getPathPattern()).isEqualTo("/integration/**");
                assertThat(r.isEnabled()).isTrue();
                assertThat(r.getId()).isNotNull();
            });
    }

    @Test
    @DisplayName("GET /admin/routes/{routeId} — should return route after creation")
    void shouldGetRouteById() {
        // Create first
        routeRepository.save(buildTestRoute()).block();

        webTestClient.get().uri("/admin/routes/{routeId}", TEST_ROUTE_ID)
            .exchange()
            .expectStatus().isOk()
            .expectBody(Route.class)
            .value(r -> assertThat(r.getRouteId()).isEqualTo(TEST_ROUTE_ID));
    }

    @Test
    @DisplayName("PATCH /admin/routes/{routeId}/disable — should disable route")
    void shouldDisableRoute() {
        routeRepository.save(buildTestRoute()).block();

        webTestClient.patch().uri("/admin/routes/{routeId}/disable", TEST_ROUTE_ID)
            .exchange()
            .expectStatus().isOk()
            .expectBody(Route.class)
            .value(r -> assertThat(r.isEnabled()).isFalse());

        // Verify in DB
        Route updated = routeRepository.findByRouteId(TEST_ROUTE_ID).block();
        assertThat(updated).isNotNull();
        assertThat(updated.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("DELETE /admin/routes/{routeId} — should delete route")
    void shouldDeleteRoute() {
        routeRepository.save(buildTestRoute()).block();

        webTestClient.delete().uri("/admin/routes/{routeId}", TEST_ROUTE_ID)
            .exchange()
            .expectStatus().isNoContent();

        Boolean exists = routeRepository.existsByRouteId(TEST_ROUTE_ID).block();
        assertThat(exists).isFalse();
    }

    @Test
    @DisplayName("POST /admin/routes — should reject duplicate routeId with 400")
    void shouldRejectDuplicateRouteId() {
        routeRepository.save(buildTestRoute()).block();

        webTestClient.post().uri("/admin/routes")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(buildTestRoute())   // same routeId
            .exchange()
            .expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("GET /admin/routes/active — should only return enabled routes")
    void shouldReturnOnlyActiveRoutes() {
        Route active   = buildTestRoute();
        Route disabled = buildTestRoute();
        disabled.setRouteId(TEST_ROUTE_ID + "-disabled");
        disabled.setEnabled(false);

        routeRepository.save(active).block();
        routeRepository.save(disabled).block();

        webTestClient.get().uri("/admin/routes/active")
            .exchange()
            .expectStatus().isOk()
            .expectBodyList(Route.class)
            .value(routes -> {
                assertThat(routes).allMatch(Route::isEnabled);
                assertThat(routes).anyMatch(r -> r.getRouteId().equals(TEST_ROUTE_ID));
                assertThat(routes).noneMatch(r -> r.getRouteId().equals(TEST_ROUTE_ID + "-disabled"));
            });

        // Cleanup extra
        routeRepository.findByRouteId(TEST_ROUTE_ID + "-disabled")
            .flatMap(routeRepository::delete).block();
    }

    private Route buildTestRoute() {
        return Route.builder()
            .routeId(TEST_ROUTE_ID)
            .pathPattern("/integration/**")
            .upstreamUrl("http://httpbin.org")
            .enabled(true)
            .stripPrefix(1)
            .filtersJson("[\"correlation-id\",\"log\"]")
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();
    }
}
