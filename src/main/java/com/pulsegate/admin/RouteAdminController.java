package com.pulsegate.admin;

import com.pulsegate.gateway.router.RouteCache;
import com.pulsegate.model.Route;
import com.pulsegate.repository.RouteRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * REST admin API for dynamic route management.
 * All mutations publish to Redis pub/sub channel to invalidate caches across all nodes.
 */
@Slf4j
@RestController
@RequestMapping("/admin/routes")
@Tag(name = "Route Management", description = "CRUD operations for gateway routes with zero-downtime hot reload")
@RequiredArgsConstructor
public class RouteAdminController {

    private static final String INVALIDATION_CHANNEL = "pulsegate:route:invalidate";

    private final RouteRepository    routeRepository;
    private final RouteCache         routeCache;
    private final ReactiveRedisTemplate<String, String> redisTemplate;

    @GetMapping
    @Operation(summary = "List all routes", description = "Returns all routes including disabled ones")
    public Flux<Route> listRoutes() {
        return routeRepository.findAll();
    }

    @GetMapping("/active")
    @Operation(summary = "List active routes", description = "Returns only enabled routes currently in cache")
    public Flux<Route> listActiveRoutes() {
        return Flux.fromIterable(routeCache.getAllCachedRoutes());
    }

    @GetMapping("/{routeId}")
    @Operation(summary = "Get route by ID")
    public Mono<Route> getRoute(@PathVariable String routeId) {
        return routeRepository.findByRouteId(routeId)
            .switchIfEmpty(Mono.error(new RouteNotFoundException(routeId)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new route", description = "Immediately available after creation — no restart required")
    public Mono<Route> createRoute(@Valid @RequestBody Route route) {
        return routeRepository.existsByRouteId(route.getRouteId())
            .flatMap(exists -> {
                if (exists) return Mono.error(new IllegalArgumentException("Route ID already exists: " + route.getRouteId()));
                route.setCreatedAt(Instant.now());
                route.setUpdatedAt(Instant.now());
                return routeRepository.save(route);
            })
            .flatMap(saved -> publishInvalidation(saved.getRouteId()).thenReturn(saved))
            .doOnNext(r -> log.info("Route created: {}", r.getRouteId()));
    }

    @PutMapping("/{routeId}")
    @Operation(summary = "Update a route", description = "Changes propagate to all nodes within ~100ms via Redis pub/sub")
    public Mono<Route> updateRoute(@PathVariable String routeId, @Valid @RequestBody Route update) {
        return routeRepository.findByRouteId(routeId)
            .switchIfEmpty(Mono.error(new RouteNotFoundException(routeId)))
            .flatMap(existing -> {
                update.setId(existing.getId());
                update.setRouteId(routeId);
                update.setCreatedAt(existing.getCreatedAt());
                update.setUpdatedAt(Instant.now());
                return routeRepository.save(update);
            })
            .flatMap(saved -> publishInvalidation(routeId).thenReturn(saved))
            .doOnNext(r -> log.info("Route updated: {}", r.getRouteId()));
    }

    @DeleteMapping("/{routeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a route", description = "Immediately removed from routing")
    public Mono<Void> deleteRoute(@PathVariable String routeId) {
        return routeRepository.findByRouteId(routeId)
            .switchIfEmpty(Mono.error(new RouteNotFoundException(routeId)))
            .flatMap(route -> routeRepository.delete(route))
            .then(publishInvalidation(routeId))
            .doOnSuccess(v -> log.info("Route deleted: {}", routeId));
    }

    @PatchMapping("/{routeId}/enable")
    @Operation(summary = "Enable a route")
    public Mono<Route> enableRoute(@PathVariable String routeId) {
        return toggleRoute(routeId, true);
    }

    @PatchMapping("/{routeId}/disable")
    @Operation(summary = "Disable a route without deleting it")
    public Mono<Route> disableRoute(@PathVariable String routeId) {
        return toggleRoute(routeId, false);
    }

    private Mono<Route> toggleRoute(String routeId, boolean enabled) {
        return routeRepository.findByRouteId(routeId)
            .switchIfEmpty(Mono.error(new RouteNotFoundException(routeId)))
            .flatMap(route -> { route.setEnabled(enabled); route.setUpdatedAt(Instant.now()); return routeRepository.save(route); })
            .flatMap(saved -> publishInvalidation(routeId).thenReturn(saved));
    }

    private Mono<Long> publishInvalidation(String routeId) {
        return redisTemplate.convertAndSend(INVALIDATION_CHANNEL, routeId)
            .doOnNext(count -> log.debug("Invalidation published for route {}, received by {} nodes", routeId, count));
    }

    static class RouteNotFoundException extends RuntimeException {
        RouteNotFoundException(String id) { super("Route not found: " + id); }
    }
}
