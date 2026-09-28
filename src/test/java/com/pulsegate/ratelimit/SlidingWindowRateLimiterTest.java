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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * Unit tests for SlidingWindowRateLimiter.
 */
@ExtendWith(MockitoExtension.class)
class SlidingWindowRateLimiterTest {

    @Mock private ReactiveRedisTemplate<String, String> redisTemplate;
    @Mock private RateLimitPolicyRepository policyRepository;
    @Mock private DefaultRedisScript<List> slidingWindowScript;

    private SlidingWindowRateLimiter rateLimiter;

    private static final Long POLICY_ID = 2L;
    private static final RateLimitPolicy TEST_POLICY = RateLimitPolicy.builder()
        .id(POLICY_ID)
        .policyName("sw-test-policy")
        .algorithm(RateLimitPolicy.Algorithm.SLIDING_WINDOW)
        .limitForPeriod(50)
        .windowSeconds(30)
        .keyType(RateLimitPolicy.KeyType.IP)
        .build();

    @BeforeEach
    void setUp() {
        rateLimiter = new SlidingWindowRateLimiter(
            redisTemplate, policyRepository, new SimpleMeterRegistry(), slidingWindowScript
        );
    }

    @Test
    @DisplayName("Should allow request when count within limit")
    void shouldAllowWhenUnderLimit() {
        when(policyRepository.findById(POLICY_ID)).thenReturn(Mono.just(TEST_POLICY));
        // Mock Redis script returning: allowed=1, remaining=49, resetMs=now+30000
        when(redisTemplate.execute(any(), anyList(), anyList()))
            .thenReturn(Flux.just(List.of(1L, 49L, System.currentTimeMillis() + 30_000)));

        StepVerifier.create(rateLimiter.isAllowed("ip:10.0.0.1", POLICY_ID))
            .assertNext(result -> {
                assertThat(result.isAllowed()).isTrue();
                assertThat(result.getRemainingRequests()).isEqualTo(49);
                assertThat(result.getLimit()).isEqualTo(50);
                assertThat(result.getAlgorithm()).isEqualTo("SLIDING_WINDOW");
            })
            .verifyComplete();
    }

    @Test
    @DisplayName("Should deny request when count exceeds limit")
    void shouldDenyWhenLimitExceeded() {
        when(policyRepository.findById(POLICY_ID)).thenReturn(Mono.just(TEST_POLICY));
        // Mock Redis script returning: allowed=0, remaining=0, resetMs=now+15000
        when(redisTemplate.execute(any(), anyList(), anyList()))
            .thenReturn(Flux.just(List.of(0L, 0L, System.currentTimeMillis() + 15_000)));

        StepVerifier.create(rateLimiter.isAllowed("ip:10.0.0.1", POLICY_ID))
            .assertNext(result -> {
                assertThat(result.isAllowed()).isFalse();
                assertThat(result.getRemainingRequests()).isZero();
                assertThat(result.getAlgorithm()).isEqualTo("SLIDING_WINDOW");
            })
            .verifyComplete();
    }

    @Test
    @DisplayName("Should fail-open when Redis is unavailable")
    void shouldFailOpenOnRedisError() {
        when(policyRepository.findById(POLICY_ID)).thenReturn(Mono.just(TEST_POLICY));
        when(redisTemplate.execute(any(), anyList(), anyList()))
            .thenReturn(Flux.error(new RuntimeException("Redis connection timeout")));

        StepVerifier.create(rateLimiter.isAllowed("ip:10.0.0.1", POLICY_ID))
            .assertNext(result -> assertThat(result.isAllowed()).isTrue())
            .verifyComplete();
    }

    @Test
    @DisplayName("Should propagate error when rate limit policy is not found")
    void shouldErrorWhenPolicyNotFound() {
        when(policyRepository.findById(POLICY_ID)).thenReturn(Mono.empty());

        StepVerifier.create(rateLimiter.isAllowed("ip:10.0.0.1", POLICY_ID))
            .expectError(IllegalArgumentException.class)
            .verify();
    }
}
