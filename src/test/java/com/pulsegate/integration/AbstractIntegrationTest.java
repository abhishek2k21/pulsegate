package com.pulsegate.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for integration tests using Testcontainers.
 *
 * Testcontainers spins up real Docker containers for PostgreSQL, Redis, and Kafka
 * so integration tests run against actual infrastructure — not mocks.
 * This catches issues that unit tests with mocks cannot: connection pool behavior,
 * Flyway migration correctness, Redis Lua script atomicity, Kafka topic creation.
 *
 * Containers are shared across all test classes (static) to avoid the overhead
 * of starting/stopping containers for each test class. Spring's
 * @DynamicPropertySource injects the container ports into the application context.
 *
 * Typical integration test run time: ~25-35 seconds (container startup is the bottleneck).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("pulsegate_test")
            .withUsername("pulsegate")
            .withPassword("pulsegate");

    @Container
    static final GenericContainer<?> redis =
        new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Container
    static final KafkaContainer kafka =
        new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // PostgreSQL
        registry.add("spring.r2dbc.url", () ->
            "r2dbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/pulsegate_test");
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);
        registry.add("spring.flyway.url",      postgres::getJdbcUrl);
        registry.add("spring.flyway.user",     postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);

        // Redis
        registry.add("pulsegate.redis.host", redis::getHost);
        registry.add("pulsegate.redis.port",  () -> redis.getMappedPort(6379).toString());
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379).toString());

        // Kafka
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);

        // Disable OAuth2 for tests
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "");
    }
}
