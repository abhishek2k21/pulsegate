package com.pulsegate.ratelimit;

import reactor.core.publisher.Mono;

/**
 * Contract for all rate limiter implementations.
 * Returns a RateLimitResult that contains the allow/deny decision
 * and headers to propagate back to the client.
 */
public interface RateLimiter {

    /**
     * @param key       Rate limit key (IP, user ID, API key, or route ID)
     * @param policyId  Identifier of the policy to enforce
     * @return          Mono of RateLimitResult — never blocking
     */
    Mono<RateLimitResult> isAllowed(String key, Long policyId);
}
