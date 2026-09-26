CREATE TABLE IF NOT EXISTS circuit_breaker_configs (
    id                          BIGSERIAL PRIMARY KEY,
    config_name                 VARCHAR(100) NOT NULL UNIQUE,
    failure_rate_threshold      FLOAT NOT NULL DEFAULT 50.0,
    slow_call_rate_threshold    FLOAT NOT NULL DEFAULT 80.0,
    slow_call_duration_ms       BIGINT NOT NULL DEFAULT 2000,
    sliding_window_size         INT NOT NULL DEFAULT 10,
    minimum_number_of_calls     INT NOT NULL DEFAULT 5,
    wait_duration_seconds       INT NOT NULL DEFAULT 30,
    permitted_calls_in_half_open INT NOT NULL DEFAULT 3,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE routes ADD CONSTRAINT fk_route_circuit_breaker
    FOREIGN KEY (circuit_breaker_config_id) REFERENCES circuit_breaker_configs(id);

-- Seed: default circuit breaker configs
INSERT INTO circuit_breaker_configs
    (config_name, failure_rate_threshold, slow_call_rate_threshold, slow_call_duration_ms,
     sliding_window_size, minimum_number_of_calls, wait_duration_seconds, permitted_calls_in_half_open)
VALUES
    ('default-cb', 50.0, 80.0, 2000, 10, 5, 30, 3),
    ('strict-cb',  25.0, 60.0, 1000, 20, 10, 60, 2),
    ('lenient-cb', 75.0, 90.0, 5000, 5,  3,  15, 5);
