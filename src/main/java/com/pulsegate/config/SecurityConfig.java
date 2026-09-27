package com.pulsegate.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Security configuration for PulseGate.
 * When OAuth2 jwk-set-uri is provided, Admin API (/admin/**) requires JWT authentication.
 * Without an IdP (local development / testing), endpoints permit requests gracefully.
 * Actuator, Swagger, WebSocket, SSE endpoints are public.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}")
    private String jwkSetUri;

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http,
            @Autowired(required = false) ReactiveJwtDecoder jwtDecoder) {
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .authorizeExchange(auth -> {
                auth.pathMatchers("/actuator/health", "/actuator/info", "/actuator/prometheus").permitAll()
                    .pathMatchers("/swagger-ui/**", "/v3/api-docs/**", "/webjars/**").permitAll()
                    .pathMatchers("/ws/**").permitAll()
                    .pathMatchers("/admin/circuit-breakers/events").permitAll();
                if (jwtDecoder != null) {
                    auth.pathMatchers("/admin/**").authenticated();
                } else {
                    auth.pathMatchers("/admin/**").permitAll();
                }
                auth.anyExchange().permitAll();
            });

        if (jwtDecoder != null) {
            http.oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtDecoder(jwtDecoder)));
        }

        return http.build();
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("!'${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}'.trim().isEmpty()")
    public ReactiveJwtDecoder jwtDecoder() {
        return NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
