package com.pulsegate.repository;

import com.pulsegate.model.Route;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Reactive R2DBC repository for gateway route persistence.
 * All methods return reactive types (Flux/Mono) for non-blocking I/O.
 */
public interface RouteRepository extends ReactiveCrudRepository<Route, Long> {

    Flux<Route> findByEnabledTrue();

    Mono<Route> findByRouteId(String routeId);

    @Query("SELECT * FROM routes WHERE enabled = true ORDER BY created_at ASC")
    Flux<Route> findAllActiveOrderedByCreation();

    Mono<Boolean> existsByRouteId(String routeId);
}
