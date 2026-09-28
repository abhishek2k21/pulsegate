package com.pulsegate.gateway.router;

import com.pulsegate.model.Route;
import com.pulsegate.repository.RouteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import reactor.core.publisher.Flux;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RouteCacheTest {

    @Mock private RouteRepository routeRepository;
    @Mock private ReactiveRedisTemplate<String, String> redisTemplate;

    private RouteCache routeCache;

    @BeforeEach
    void setUp() {
        Route route1 = Route.builder()
            .routeId("orders")
            .pathPattern("/api/v1/orders/**")
            .upstreamUrl("http://order-service:8080")
            .enabled(true)
            .build();

        Route route2 = Route.builder()
            .routeId("users-exact")
            .pathPattern("/api/v1/users/profile")
            .upstreamUrl("http://user-service:8080")
            .enabled(true)
            .build();

        Route route3 = Route.builder()
            .routeId("users-wildcard")
            .pathPattern("/api/v1/users/*")
            .upstreamUrl("http://user-service:8080")
            .enabled(true)
            .build();

        when(routeRepository.findByEnabledTrue()).thenReturn(Flux.just(route1, route2, route3));
        when(redisTemplate.listenToChannel("pulsegate:route:invalidate")).thenReturn(Flux.empty());

        routeCache = new RouteCache(routeRepository, redisTemplate);
        routeCache.initialize();
    }

    @Test
    @DisplayName("Should match multi-segment glob pattern (/**)")
    void shouldMatchMultiSegmentGlob() {
        Optional<Route> match = routeCache.findMatchingRoute("/api/v1/orders/123/items/456");
        assertThat(match).isPresent();
        assertThat(match.get().getRouteId()).isEqualTo("orders");
    }

    @Test
    @DisplayName("Should match exact path pattern")
    void shouldMatchExactPath() {
        Optional<Route> match = routeCache.findMatchingRoute("/api/v1/users/profile");
        assertThat(match).isPresent();
        assertThat(match.get().getRouteId()).isEqualTo("users-exact");
    }

    @Test
    @DisplayName("Should match single-segment wildcard (/*)")
    void shouldMatchSingleSegmentWildcard() {
        Optional<Route> match = routeCache.findMatchingRoute("/api/v1/users/123");
        assertThat(match).isPresent();
        assertThat(match.get().getRouteId()).isEqualTo("users-wildcard");
    }

    @Test
    @DisplayName("Should return empty optional for non-matching path")
    void shouldReturnEmptyForNonMatchingPath() {
        Optional<Route> match = routeCache.findMatchingRoute("/nonexistent/path");
        assertThat(match).isEmpty();
    }

    @Test
    @DisplayName("Boundary case: empty or root path should not crash")
    void boundaryCase_emptyOrRootPath() {
        assertThat(routeCache.findMatchingRoute("")).isEmpty();
        assertThat(routeCache.findMatchingRoute("/")).isEmpty();
    }
}
