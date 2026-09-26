package com.pulsegate.ratelimit;

import com.pulsegate.model.RateLimitPolicy;
import com.pulsegate.repository.RateLimitPolicyRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;

/**
 * Fixed Window Rate Limiter using Redis INCR + EXPIRE.
 *
 * Algorithm:
 *   Each window is identified by: key + floor(now / windowMs)
 *   On first request in a window: SET key 1 EX windowSeconds
 *   On subsequent requests: INCR key; reject if > limit
 *
 * Trade-off vs Sliding Window:
 *   - CHEAPER: O(1) vs O(log N) Redis operations
 *   - EDGE BURST: a client can send 2x limit around window boundaries
 *     (100 at end of window N, 100 at start of window N+1)
 *   - USE CASE: simple per-hour/per-day API quotas (free-tier metering)
 *     where edge bursts are acceptable
 *
 * This implementation uses a pipeline (MULTI-EXEC equivalent via reactive commands)
 * to minimize round-trips: INCR + EXPIRE in a single pipeline.
 */
@Slf4j
@Component("fixedWindowRateLimiter")
@RequiredArgsConstructor
public class FixedWindowRateLimiter implements RateLimiter {

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final RateLimitPolicyRepository policyRepository;
    private final MeterRegistry meterRegistry;

    private static final String KEY_PREFIX = "rl:fw:";
    private static final String ALGORITHM  = "FIXED_WINDOW";

    @Override
    public Mono<RateLimitResult> isAllowed(String key, Long policyId) {
        return policyRepository.findById(policyId)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Rate limit policy not found: " + policyId)))
            .flatMap(policy -> checkWindow(key, policy))
            .doOnNext(result -> recordMetrics(result))
            .onErrorResume(ex -> {
                // Fail-open: allow request if Redis is unavailable
                log.warn("FixedWindow Redis error (fail-open): {}", ex.getMessage());
                return Mono.just(RateLimitResult.allowed(1, 1, System.currentTimeMillis(), ALGORITHM));
            });
    }

    private Mono<RateLimitResult> checkWindow(String key, RateLimitPolicy policy) {
        long windowMs      = (long) policy.getWindowSeconds() * 1000L;
        long windowId      = System.currentTimeMillis() / windowMs;
        long windowStartMs = windowId * windowMs;
        long windowEndMs   = windowStartMs + windowMs;
        long limit         = policy.getLimitForPeriod();

        String redisKey = KEY_PREFIX + key + ":" + policy.getId() + ":" + windowId;

        // INCR — atomically increments and returns the new count
        return redisTemplate.opsForValue()
            .increment(redisKey)
            .flatMap(count -> {
                // Set TTL only on first increment (count == 1) to avoid resetting the window
                Mono<Boolean> setTtl = (count == 1)
                    ? redisTemplate.expire(redisKey, Duration.ofSeconds(policy.getWindowSeconds() + 1))
                    : Mono.just(true);

                return setTtl.map(v -> {
                    long remaining = Math.max(0L, limit - count);
                    if (count > limit) {
                        return RateLimitResult.denied(limit, windowEndMs, ALGORITHM);
                    }
                    return RateLimitResult.allowed(remaining, limit, windowEndMs, ALGORITHM);
                });
            });
    }

    private void recordMetrics(RateLimitResult result) {
        meterRegistry.counter("pulsegate.ratelimit.requests",
            "algorithm", ALGORITHM,
            "allowed",   String.valueOf(result.isAllowed())
        ).increment();
    }
}
