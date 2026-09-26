CREATE TABLE IF NOT EXISTS routes (
    id                      BIGSERIAL PRIMARY KEY,
    route_id                VARCHAR(100) NOT NULL UNIQUE,
    path_pattern            VARCHAR(255) NOT NULL,
    upstream_url            VARCHAR(512) NOT NULL,
    filters_json            TEXT DEFAULT '["correlation-id","rate-limit","circuit-breaker","log"]',
    enabled                 BOOLEAN NOT NULL DEFAULT TRUE,
    rate_limit_policy_id    BIGINT,
    circuit_breaker_config_id BIGINT,
    allowed_methods_json    TEXT,
    strip_prefix            INT NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_routes_enabled      ON routes(enabled);
CREATE INDEX idx_routes_path_pattern ON routes(path_pattern);

COMMENT ON TABLE  routes IS 'Gateway routing rules — loaded into in-memory cache with Redis pub/sub invalidation';
COMMENT ON COLUMN routes.route_id IS 'Unique slug identifier (e.g. order-service, product-api)';
COMMENT ON COLUMN routes.path_pattern IS 'Glob path pattern (e.g. /api/orders/**)';
COMMENT ON COLUMN routes.strip_prefix IS 'Number of path segments to strip before forwarding (e.g. 1 turns /api/orders into /orders)';
