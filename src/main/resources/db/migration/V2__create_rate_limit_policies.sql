CREATE TYPE rate_limit_algorithm AS ENUM ('TOKEN_BUCKET', 'SLIDING_WINDOW', 'FIXED_WINDOW');
CREATE TYPE rate_limit_key_type  AS ENUM ('IP', 'USER_ID', 'ROUTE', 'API_KEY');

CREATE TABLE IF NOT EXISTS rate_limit_policies (
    id                  BIGSERIAL PRIMARY KEY,
    policy_name         VARCHAR(100) NOT NULL UNIQUE,
    algorithm           rate_limit_algorithm NOT NULL DEFAULT 'TOKEN_BUCKET',
    limit_for_period    INT NOT NULL,
    window_seconds      INT NOT NULL,
    key_type            rate_limit_key_type NOT NULL DEFAULT 'IP',
    refill_tokens       INT NOT NULL DEFAULT 1,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE routes ADD CONSTRAINT fk_route_rate_limit
    FOREIGN KEY (rate_limit_policy_id) REFERENCES rate_limit_policies(id);

-- Seed: default policies
INSERT INTO rate_limit_policies (policy_name, algorithm, limit_for_period, window_seconds, key_type, refill_tokens)
VALUES
    ('default-token-bucket', 'TOKEN_BUCKET',   100, 60, 'IP',      1),
    ('strict-sliding-window','SLIDING_WINDOW',  10,  1,  'IP',      1),
    ('free-tier-quota',      'FIXED_WINDOW',    1000,3600,'API_KEY', 1);
