package com.pulsegate.ratelimit;

import com.pulsegate.model.RateLimitPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Factory that selects the appropriate RateLimiter implementation
 * based on the policy's configured algorithm.
 *
 * Spring injects all RateLimiter beans into the map keyed by bean name.
 * This avoids switch statements scattered across filters and makes adding
 * new algorithms a matter of adding a new @Component — no other changes needed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimiterFactory {

    private final Map<String, RateLimiter> rateLimiters;

    public RateLimiter forAlgorithm(RateLimitPolicy.Algorithm algorithm) {
        String beanName = switch (algorithm) {
            case TOKEN_BUCKET   -> "tokenBucketRateLimiter";
            case SLIDING_WINDOW -> "slidingWindowRateLimiter";
            case FIXED_WINDOW   -> "fixedWindowRateLimiter";
        };

        RateLimiter limiter = rateLimiters.get(beanName);
        if (limiter == null) {
            log.error("No RateLimiter bean found for algorithm: {} (bean: {})", algorithm, beanName);
            throw new IllegalStateException("No RateLimiter implementation for: " + algorithm);
        }
        return limiter;
    }
}
