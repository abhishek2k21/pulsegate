package com.pulsegate.gateway.router;

import com.pulsegate.model.Route;
import com.pulsegate.repository.RouteRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * In-memory route cache with Redis pub/sub invalidation.
 *
 * Startup: loads all active routes from PostgreSQL into memory.
 * Runtime: subscribes to Redis channel "pulsegate:route:invalidate" —
 *          when admin API modifies a route, it publishes the routeId
 *          to this channel, triggering a targeted cache refresh.
 *
 * This avoids a DB round-trip per request (critical for sub-ms proxy latency)
 * while ensuring route updates propagate to all gateway nodes within ~100ms.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RouteCache {

    private static final String INVALIDATION_CHANNEL = "pulsegate:route:invalidate";

    private final RouteRepository routeRepository;
    private final ReactiveRedisTemplate<String, String> redisTemplate;

    /** Thread-safe route map: routeId → Route */
    private final Map<String, Route> routeMap = new ConcurrentHashMap<>();

    /** Compiled path patterns for efficient matching */
    private final Map<String, Pattern> patternCache = new ConcurrentHashMap<>();

    @PostConstruct
    public void initialize() {
        loadAllRoutes();
        subscribeToInvalidationChannel();
    }

    /**
     * Finds the best matching route for an incoming request path.
     * Uses glob-to-regex compiled patterns cached at route load time.
     */
    public Optional<Route> findMatchingRoute(String requestPath) {
        return routeMap.values().stream()
            .filter(route -> {
                Pattern pattern = patternCache.get(route.getRouteId());
                return pattern != null && pattern.matcher(requestPath).matches();
            })
            .findFirst();
    }

    public void invalidate(String routeId) {
        log.info("Cache invalidation triggered for route: {}", routeId);
        routeRepository.findByRouteId(routeId)
            .subscribe(route -> {
                routeMap.put(routeId, route);
                patternCache.put(routeId, globToPattern(route.getPathPattern()));
                log.info("Route refreshed in cache: {}", routeId);
            }, error -> log.error("Failed to refresh route {}: {}", routeId, error.getMessage()),
               () -> { if (!routeMap.containsKey(routeId)) { routeMap.remove(routeId); patternCache.remove(routeId); } });
    }

    /** Periodic full reload as a safety net for missed invalidation events. */
    @Scheduled(fixedRateString = "\")
    public void periodicReload() {
        log.debug("Periodic route cache reload started");
        loadAllRoutes();
    }

    private void loadAllRoutes() {
        routeRepository.findByEnabledTrue()
            .collectList()
            .subscribe(routes -> {
                routeMap.clear();
                patternCache.clear();
                routes.forEach(r -> {
                    routeMap.put(r.getRouteId(), r);
                    patternCache.put(r.getRouteId(), globToPattern(r.getPathPattern()));
                });
                log.info("Route cache loaded with {} routes", routes.size());
            }, error -> log.error("Route cache load failed: {}", error.getMessage()));
    }

    private void subscribeToInvalidationChannel() {
        redisTemplate.listenToChannel(INVALIDATION_CHANNEL)
            .subscribe(message -> invalidate(message.getMessage()),
                       error -> log.error("Redis invalidation subscription error: {}", error.getMessage()));
    }

    /**
     * Converts glob patterns (e.g. /api/orders/**) to compiled regex patterns.
     * Supports: * (single segment), ** (multi-segment), ? (single char)
     */
    private Pattern globToPattern(String glob) {
        String regex = glob
            .replace(".", "\\.")
            .replace("**", "MULTI_WILDCARD")
            .replace("*", "[^/]+")
            .replace("MULTI_WILDCARD", ".*")
            .replace("?", "[^/]");
        return Pattern.compile("^" + regex + "$");
    }

    public List<Route> getAllCachedRoutes() {
        return List.copyOf(routeMap.values());
    }
}
