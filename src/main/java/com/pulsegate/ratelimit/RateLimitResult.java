package com.pulsegate.ratelimit;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/**
 * Result of a rate limit check. Includes HTTP header values per RFC 6585 and
 * the IETF draft RateLimit header specification.
 */
@Data
@Builder
@AllArgsConstructor
public class RateLimitResult {

    private final boolean allowed;

    /** Remaining requests in the current window. */
    private final long remainingRequests;

    /** Total limit for the current window/bucket. */
    private final long limit;

    /** Epoch milliseconds when the limit resets. */
    private final long resetEpochMs;

    /** Algorithm that produced this result. */
    private final String algorithm;

    public static RateLimitResult allowed(long remaining, long limit, long resetMs, String algo) {
        return RateLimitResult.builder()
            .allowed(true).remainingRequests(remaining).limit(limit)
            .resetEpochMs(resetMs).algorithm(algo).build();
    }

    public static RateLimitResult denied(long limit, long resetMs, String algo) {
        return RateLimitResult.builder()
            .allowed(false).remainingRequests(0).limit(limit)
            .resetEpochMs(resetMs).algorithm(algo).build();
    }
}
