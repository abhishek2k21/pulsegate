package com.pulsegate.ratelimit;

import com.pulsegate.model.RateLimitPolicy;
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
 * Token Bucket Rate Limiter backed by Redis Lua scripts.
 *
 * Algorithm: A bucket holds up to 'capacity' tokens. Tokens are added at
 * 'refillRate' per refill interval. Each request consumes one token.
 * Allows short bursts (up to capacity) while enforcing long-term average rate.
 *
 * Atomicity: The Lua script executes atomically on the Redis server,
 * ensuring zero race conditions even across N gateway instances.
 */
@Slf4j
@Component("tokenBucketRateLimiter")
@RequiredArgsConstructor
public class TokenBucketRateLimiter implements RateLimiter {

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final RateLimitPolicyRepository policyRepository;
    private final MeterRegistry meterRegistry;

    @Qualifier("tokenBucketScript")
    private final DefaultRedisScript<List> tokenBucketScript;

    private static final String KEY_PREFIX = "rl:tb:";
    private static final String ALGORITHM  = "TOKEN_BUCKET";

    @Override
    public Mono<RateLimitResult> isAllowed(String key, Long policyId) {
        return policyRepository.findById(policyId)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Rate limit policy not found: " + policyId)))
            .flatMap(policy -> executeScript(key, policy))
            .doOnNext(result -> recordMetrics(key, result))
            .onErrorResume(ex -> {
                // Fail-open: if Redis is unavailable, allow the request
                log.warn("Rate limiter Redis error (fail-open): key={}, error={}", key, ex.getMessage());
                return Mono.just(RateLimitResult.allowed(1, 1, System.currentTimeMillis(), ALGORITHM));
            });
    }

    private Mono<RateLimitResult> executeScript(String key, RateLimitPolicy policy) {
        String redisKey      = KEY_PREFIX + key + ":" + policy.getId();
        long   capacity      = policy.getLimitForPeriod();
        long   refillTokens  = policy.getRefillTokens() > 0 ? policy.getRefillTokens() : 1;
        long   intervalMs    = (long) policy.getWindowSeconds() * 1000;
        long   nowMs         = System.currentTimeMillis();

        return redisTemplate.execute(
                tokenBucketScript,
                List.of(redisKey),
                List.of(String.valueOf(capacity), String.valueOf(refillTokens),
                        String.valueOf(intervalMs), String.valueOf(nowMs))
            )
            .collectList()
            .map(results -> {
                List<?> result = (List<?>) results.get(0);
                boolean allowed   = Long.parseLong(result.get(0).toString()) == 1L;
                long    remaining = Long.parseLong(result.get(1).toString());
                long    resetMs   = Long.parseLong(result.get(2).toString());
                return allowed
                    ? RateLimitResult.allowed(remaining, capacity, resetMs, ALGORITHM)
                    : RateLimitResult.denied(capacity, resetMs, ALGORITHM);
            });
    }

    private void recordMetrics(String key, RateLimitResult result) {
        meterRegistry.counter("pulsegate.ratelimit.requests",
            "algorithm", ALGORITHM,
            "allowed",   String.valueOf(result.isAllowed()),
            "key_prefix", key.split(":")[0]
        ).increment();
    }
}
