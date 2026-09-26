package com.pulsegate.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Configuration for a rate limiting policy.
 * Supports three algorithms: TOKEN_BUCKET, SLIDING_WINDOW, FIXED_WINDOW.
 *
 * Algorithm selection guide:
 *   TOKEN_BUCKET   → Best for bursty traffic (mobile, user-facing); allows short bursts.
 *   SLIDING_WINDOW → Strict fairness; ideal for payment APIs, OTPs.
 *   FIXED_WINDOW   → Simple quota limits; free-tier API metering.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("rate_limit_policies")
public class RateLimitPolicy {

    @Id
    private Long id;

    @Column("policy_name")
    private String policyName;

    /** Rate limiting algorithm to use. */
    @Column("algorithm")
    private Algorithm algorithm;

    /** Max requests allowed per window / bucket capacity. */
    @Column("limit_for_period")
    private int limitForPeriod;

    /** Window duration in seconds (for SLIDING_WINDOW, FIXED_WINDOW).
     *  For TOKEN_BUCKET: refill interval in seconds. */
    @Column("window_seconds")
    private int windowSeconds;

    /** KEY_TYPE determines how the rate limit key is resolved:
     *    IP       → per client IP
     *    USER_ID  → per authenticated user
     *    ROUTE    → shared limit for all clients on a route (global throttle)
     *    API_KEY  → per API key header */
    @Column("key_type")
    private KeyType keyType;

    /** For TOKEN_BUCKET: tokens added per refill interval. */
    @Column("refill_tokens")
    private int refillTokens;

    public enum Algorithm { TOKEN_BUCKET, SLIDING_WINDOW, FIXED_WINDOW }
    public enum KeyType   { IP, USER_ID, ROUTE, API_KEY }
}
