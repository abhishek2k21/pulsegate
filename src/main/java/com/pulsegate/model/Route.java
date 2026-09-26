package com.pulsegate.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.List;

/**
 * Represents a dynamically-managed gateway route stored in PostgreSQL.
 * Routes are loaded into an in-memory cache at startup and refreshed
 * on admin API changes via Redis pub/sub invalidation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("routes")
public class Route {

    @Id
    private Long id;

    /** Unique route identifier used in path matching and metrics labels. */
    @Column("route_id")
    private String routeId;

    /** Glob pattern for incoming request path, e.g. /api/orders/** */
    @Column("path_pattern")
    private String pathPattern;

    /** Target upstream base URL, e.g. http://order-service:8080 */
    @Column("upstream_url")
    private String upstreamUrl;

    /** Ordered list of filter names to apply (e.g. ["auth","rate-limit","circuit-breaker"]). */
    @Column("filters")
    private String filtersJson;   // stored as JSON string, deserialized on load

    /** Whether this route is currently active. Soft-disable without deletion. */
    @Column("enabled")
    private boolean enabled;

    /** Rate limit policy ID associated with this route (nullable). */
    @Column("rate_limit_policy_id")
    private Long rateLimitPolicyId;

    /** Circuit breaker config ID associated with this route (nullable). */
    @Column("circuit_breaker_config_id")
    private Long circuitBreakerConfigId;

    /** HTTP methods allowed; null means all methods. */
    @Column("allowed_methods")
    private String allowedMethodsJson;

    /** Strip prefix segments before forwarding. e.g. strip /api gives /orders to upstream */
    @Column("strip_prefix")
    private int stripPrefix;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;
}
