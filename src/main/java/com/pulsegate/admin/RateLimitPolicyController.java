package com.pulsegate.admin;

import com.pulsegate.model.RateLimitPolicy;
import com.pulsegate.repository.RateLimitPolicyRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * CRUD API for rate limit policies.
 * Policies can be attached to routes via the route's rateLimitPolicyId field.
 */
@RestController
@RequestMapping("/admin/rate-limit-policies")
@Tag(name = "Rate Limit Policies", description = "Manage rate limiting policies (Token Bucket, Sliding Window, Fixed Window)")
@RequiredArgsConstructor
public class RateLimitPolicyController {

    private final RateLimitPolicyRepository repository;

    @GetMapping
    @Operation(summary = "List all rate limit policies")
    public Flux<RateLimitPolicy> listPolicies() {
        return repository.findAll();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get policy by ID")
    public Mono<RateLimitPolicy> getPolicy(@PathVariable Long id) {
        return repository.findById(id)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Policy not found: " + id)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new rate limit policy",
               description = "Supported algorithms: TOKEN_BUCKET, SLIDING_WINDOW, FIXED_WINDOW. Key types: IP, USER_ID, ROUTE, API_KEY")
    public Mono<RateLimitPolicy> createPolicy(@Valid @RequestBody RateLimitPolicy policy) {
        return repository.save(policy);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update an existing policy")
    public Mono<RateLimitPolicy> updatePolicy(@PathVariable Long id, @Valid @RequestBody RateLimitPolicy update) {
        return repository.findById(id)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Policy not found: " + id)))
            .flatMap(existing -> { update.setId(id); return repository.save(update); });
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a rate limit policy")
    public Mono<Void> deletePolicy(@PathVariable Long id) {
        return repository.deleteById(id);
    }
}
