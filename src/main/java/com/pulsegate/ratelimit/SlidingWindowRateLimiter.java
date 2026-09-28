package com.pulsegate.ratelimit;

import com.pulsegate.repository.RateLimitPolicyRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Sliding Window Rate Limiter using Redis Sorted Sets.
 *
 * Algorithm: Maintains a sorted set of request timestamps per key.
 * For each request: remove entries older than (now - windowMs),
 * count remaining entries, reject if >= limit, otherwise add and allow.
 *
 * This eliminates the "edge burst" problem of fixed windows where a client
 * can send 2x the limit by timing requests around window boundaries.
 */
@Slf4j
@Component("slidingWindowRateLimiter")
@RequiredArgsConstructor
public class SlidingWindowRateLimiter implements RateLimiter {

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final RateLimitPolicyRepository policyRepository;
    private final MeterRegistry meterRegistry;

    @Qualifier("slidingWindowScript")
    private final DefaultRedisScript<List> slidingWindowScript;

    private static final String KEY_PREFIX = "rl:sw:";
    private static final String ALGORITHM  = "SLIDING_WINDOW";

    @Override
    public Mono<RateLimitResult> isAllowed(String key, Long policyId) {
        return policyRepository.findById(policyId)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Rate limit policy not found: " + policyId)))
            .flatMap(policy -> executeScript(key, policy))
            .doOnNext(result -> recordMetrics(key, result))
            .onErrorResume(ex -> !(ex instanceof IllegalArgumentException), ex -> {
                log.warn("SlidingWindow Redis error (fail-open): {}", ex.getMessage());
                return Mono.just(RateLimitResult.allowed(1, 1, System.currentTimeMillis(), ALGORITHM));
            });
    }

    private Mono<RateLimitResult> executeScript(String key, com.pulsegate.model.RateLimitPolicy policy) {
        String redisKey  = KEY_PREFIX + key + ":" + policy.getId();
        long   limit     = policy.getLimitForPeriod();
        long   windowMs  = (long) policy.getWindowSeconds() * 1000;
        long   nowMs     = System.currentTimeMillis();

        return redisTemplate.execute(
                slidingWindowScript,
                List.of(redisKey),
                List.of(String.valueOf(limit), String.valueOf(windowMs), String.valueOf(nowMs))
            )
            .collectList()
            .map(results -> {
                List<?> result    = (List<?>) results.get(0);
                boolean allowed   = Long.parseLong(result.get(0).toString()) == 1L;
                long    remaining = Long.parseLong(result.get(1).toString());
                long    resetMs   = Long.parseLong(result.get(2).toString());
                return allowed
                    ? RateLimitResult.allowed(remaining, limit, resetMs, ALGORITHM)
                    : RateLimitResult.denied(limit, resetMs, ALGORITHM);
            });
    }

    private void recordMetrics(String key, RateLimitResult result) {
        meterRegistry.counter("pulsegate.ratelimit.requests",
            "algorithm", ALGORITHM,
            "allowed",   String.valueOf(result.isAllowed())
        ).increment();
    }
}
