package com.pulsegate.ratelimit;

import com.pulsegate.model.RateLimitPolicy;
import com.pulsegate.repository.RateLimitPolicyRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Unit tests for TokenBucketRateLimiter.
 * Redis calls are mocked — see RateLimitIntegrationTest for Testcontainers-based tests.
 */
@ExtendWith(MockitoExtension.class)
class TokenBucketRateLimiterTest {

    @Mock private ReactiveRedisTemplate<String, String> redisTemplate;
    @Mock private RateLimitPolicyRepository policyRepository;
    @Mock private DefaultRedisScript<List> tokenBucketScript;

    private TokenBucketRateLimiter rateLimiter;

    private static final Long POLICY_ID = 1L;
    private static final RateLimitPolicy TEST_POLICY = RateLimitPolicy.builder()
        .id(POLICY_ID)
        .policyName("test-policy")
        .algorithm(RateLimitPolicy.Algorithm.TOKEN_BUCKET)
        .limitForPeriod(100)
        .windowSeconds(60)
        .refillTokens(1)
        .keyType(RateLimitPolicy.KeyType.IP)
        .build();

    @BeforeEach
    void setUp() {
        rateLimiter = new TokenBucketRateLimiter(
            redisTemplate, policyRepository, new SimpleMeterRegistry(), tokenBucketScript
        );
    }

    @Test
    @DisplayName("Should allow request when tokens are available")
    void shouldAllowWhenTokensAvailable() {
        when(policyRepository.findById(POLICY_ID)).thenReturn(Mono.just(TEST_POLICY));
        // Mock Redis script returning: allowed=1, remaining=99, resetMs=now+60000
        when(redisTemplate.execute(any(), anyList(), anyList()))
            .thenReturn(Flux.just(List.of(1L, 99L, System.currentTimeMillis() + 60_000)));

        StepVerifier.create(rateLimiter.isAllowed("ip:192.168.1.1", POLICY_ID))
            .assertNext(result -> {
                assertThat(result.isAllowed()).isTrue();
                assertThat(result.getRemainingRequests()).isEqualTo(99);
                assertThat(result.getAlgorithm()).isEqualTo("TOKEN_BUCKET");
            })
            .verifyComplete();
    }

    @Test
    @DisplayName("Should deny request when bucket is empty")
    void shouldDenyWhenBucketEmpty() {
        when(policyRepository.findById(POLICY_ID)).thenReturn(Mono.just(TEST_POLICY));
        when(redisTemplate.execute(any(), anyList(), anyList()))
            .thenReturn(Flux.just(List.of(0L, 0L, System.currentTimeMillis() + 5_000)));

        StepVerifier.create(rateLimiter.isAllowed("ip:192.168.1.1", POLICY_ID))
            .assertNext(result -> {
                assertThat(result.isAllowed()).isFalse();
                assertThat(result.getRemainingRequests()).isZero();
            })
            .verifyComplete();
    }

    @Test
    @DisplayName("Should fail-open when Redis is unavailable")
    void shouldFailOpenOnRedisError() {
        when(policyRepository.findById(POLICY_ID)).thenReturn(Mono.just(TEST_POLICY));
        when(redisTemplate.execute(any(), anyList(), anyList()))
            .thenReturn(Flux.error(new RuntimeException("Redis connection refused")));

        // Fail-open: request should be ALLOWED despite Redis error
        StepVerifier.create(rateLimiter.isAllowed("ip:192.168.1.1", POLICY_ID))
            .assertNext(result -> assertThat(result.isAllowed()).isTrue())
            .verifyComplete();
    }

    @Test
    @DisplayName("Should return error when policy not found")
    void shouldErrorWhenPolicyNotFound() {
        when(policyRepository.findById(POLICY_ID)).thenReturn(Mono.empty());

        StepVerifier.create(rateLimiter.isAllowed("ip:192.168.1.1", POLICY_ID))
            .expectError(IllegalArgumentException.class)
            .verify();
    }
}
