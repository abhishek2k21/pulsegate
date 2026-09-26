package com.pulsegate.repository;

import com.pulsegate.model.RateLimitPolicy;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface RateLimitPolicyRepository extends ReactiveCrudRepository<RateLimitPolicy, Long> {
    Mono<RateLimitPolicy> findByPolicyName(String policyName);
}
