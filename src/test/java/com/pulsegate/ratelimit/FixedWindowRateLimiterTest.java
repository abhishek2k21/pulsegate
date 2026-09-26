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
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FixedWindowRateLimiterTest {

    @Mock private ReactiveRedisTemplate<String, String> redisTemplate;
    @Mock private ReactiveValueOperations<String, String> valueOps;
    @Mock private RateLimitPolicyRepository policyRepository;

    private FixedWindowRateLimiter rateLimiter;

    private static final Long POLICY_ID = 1L;
    private static final RateLimitPolicy TEST_POLICY = RateLimitPolicy.builder()
        .id(POLICY_ID).policyName("fw-test").algorithm(RateLimitPolicy.Algorithm.FIXED_WINDOW)
        .limitForPeriod(10).windowSeconds(60).refillTokens(1).keyType(RateLimitPolicy.KeyType.IP).build();

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        rateLimiter = new FixedWindowRateLimiter(redisTemplate, policyRepository, new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("Should allow request when count is below limit")
    void shouldAllowWhenBelowLimit() {
        when(policyRepository.findById(POLICY_ID)).thenReturn(Mono.just(TEST_POLICY));
        when(valueOps.increment(anyString())).thenReturn(Mono.just(5L));    // 5 < 10
        when(redisTemplate.expire(anyString(), any())).thenReturn(Mono.just(true));

        StepVerifier.create(rateLimiter.isAllowed("ip:10.0.0.1", POLICY_ID))
            .assertNext(result -> {
                assertThat(result.isAllowed()).isTrue();
                assertThat(result.getRemainingRequests()).isEqualTo(5L); // 10-5
                assertThat(result.getAlgorithm()).isEqualTo("FIXED_WINDOW");
            })
            .verifyComplete();
    }

    @Test
    @DisplayName("Should deny request when count exceeds limit")
    void shouldDenyWhenOverLimit() {
        when(policyRepository.findById(POLICY_ID)).thenReturn(Mono.just(TEST_POLICY));
        when(valueOps.increment(anyString())).thenReturn(Mono.just(11L));   // 11 > 10
        when(redisTemplate.expire(anyString(), any())).thenReturn(Mono.just(true));

        StepVerifier.create(rateLimiter.isAllowed("ip:10.0.0.1", POLICY_ID))
            .assertNext(result -> {
                assertThat(result.isAllowed()).isFalse();
                assertThat(result.getRemainingRequests()).isZero();
            })
            .verifyComplete();
    }

    @Test
    @DisplayName("Should fail-open when Redis is down")
    void shouldFailOpenOnRedisError() {
        when(policyRepository.findById(POLICY_ID)).thenReturn(Mono.just(TEST_POLICY));
        when(valueOps.increment(anyString()))
            .thenReturn(Mono.error(new RuntimeException("Redis ECONNREFUSED")));

        StepVerifier.create(rateLimiter.isAllowed("ip:10.0.0.1", POLICY_ID))
            .assertNext(result -> assertThat(result.isAllowed()).isTrue())
            .verifyComplete();
    }
}
