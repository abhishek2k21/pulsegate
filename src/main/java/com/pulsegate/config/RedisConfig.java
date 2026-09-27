package com.pulsegate.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.TimeoutOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.beans.factory.annotation.Value;

import java.time.Duration;

/**
 * Redis configuration with Lettuce reactive client.
 * Uses auto-reconnect and command timeout to handle transient Redis failures
 * without blocking the request pipeline.
 *
 * Rate limiter Lua scripts are registered here as beans so they are
 * preloaded at startup (SHA cached by Redis for efficient EVALSHA calls).
 */
@Configuration
public class RedisConfig {

    @Value("${pulsegate.redis.host:localhost}")
    private String redisHost;

    @Value("${pulsegate.redis.port:6379}")
    private int redisPort;

    @Value("${pulsegate.redis.command-timeout-ms:500}")
    private long commandTimeoutMs;

    @Bean
    @Primary
    public ReactiveRedisConnectionFactory reactiveRedisConnectionFactory() {
        RedisStandaloneConfiguration serverConfig = new RedisStandaloneConfiguration(redisHost, redisPort);

        ClientOptions clientOptions = ClientOptions.builder()
            .autoReconnect(true)
            .timeoutOptions(TimeoutOptions.enabled(Duration.ofMillis(commandTimeoutMs)))
            .build();

        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
            .commandTimeout(Duration.ofMillis(commandTimeoutMs))
            .clientOptions(clientOptions)
            .build();

        return new LettuceConnectionFactory(serverConfig, clientConfig);
    }

    @Bean
    @Primary
    public ReactiveRedisTemplate<String, String> reactiveRedisTemplate(
            ReactiveRedisConnectionFactory factory) {
        StringRedisSerializer serializer = new StringRedisSerializer();
        RedisSerializationContext<String, String> context =
            RedisSerializationContext.<String, String>newSerializationContext(serializer)
                .value(serializer)
                .build();
        return new ReactiveRedisTemplate<>(factory, context);
    }

    /**
     * Token Bucket Lua script — atomically checks and decrements bucket tokens.
     * Returns [allowed (1/0), remaining_tokens, reset_time_epoch_ms]
     */
    @Bean("tokenBucketScript")
    public DefaultRedisScript<java.util.List> tokenBucketScript() {
        DefaultRedisScript<java.util.List> script = new DefaultRedisScript<>();
        script.setResultType(java.util.List.class);
        script.setScriptText(
            "local key = KEYS[1] " +
            "local capacity = tonumber(ARGV[1]) " +
            "local refill_rate = tonumber(ARGV[2]) " +
            "local refill_interval_ms = tonumber(ARGV[3]) " +
            "local now = tonumber(ARGV[4]) " +
            "local bucket = redis.call('HMGET', key, 'tokens', 'last_refill') " +
            "local tokens = tonumber(bucket[1]) or capacity " +
            "local last_refill = tonumber(bucket[2]) or now " +
            "local elapsed = now - last_refill " +
            "local refill_count = math.floor(elapsed / refill_interval_ms) " +
            "if refill_count > 0 then " +
            "  tokens = math.min(capacity, tokens + refill_count * refill_rate) " +
            "  last_refill = last_refill + refill_count * refill_interval_ms " +
            "end " +
            "local allowed = 0 " +
            "if tokens >= 1 then " +
            "  tokens = tokens - 1 " +
            "  allowed = 1 " +
            "end " +
            "local ttl = math.ceil((capacity / refill_rate) * refill_interval_ms / 1000) + 1 " +
            "redis.call('HMSET', key, 'tokens', tokens, 'last_refill', last_refill) " +
            "redis.call('EXPIRE', key, ttl) " +
            "local reset_ms = last_refill + refill_interval_ms " +
            "return {allowed, tokens, reset_ms}"
        );
        return script;
    }

    /**
     * Sliding Window Lua script — uses sorted set with timestamps as scores.
     * Removes entries outside the window, counts remaining, adds current request.
     */
    @Bean("slidingWindowScript")
    public DefaultRedisScript<java.util.List> slidingWindowScript() {
        DefaultRedisScript<java.util.List> script = new DefaultRedisScript<>();
        script.setResultType(java.util.List.class);
        script.setScriptText(
            "local key = KEYS[1] " +
            "local limit = tonumber(ARGV[1]) " +
            "local window_ms = tonumber(ARGV[2]) " +
            "local now = tonumber(ARGV[3]) " +
            "local window_start = now - window_ms " +
            "redis.call('ZREMRANGEBYSCORE', key, '-inf', window_start) " +
            "local count = redis.call('ZCARD', key) " +
            "local allowed = 0 " +
            "local remaining = 0 " +
            "if count < limit then " +
            "  redis.call('ZADD', key, now, now) " +
            "  redis.call('PEXPIRE', key, window_ms + 1000) " +
            "  allowed = 1 " +
            "  remaining = limit - count - 1 " +
            "else " +
            "  remaining = 0 " +
            "end " +
            "local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES') " +
            "local reset_ms = 0 " +
            "if #oldest > 0 then reset_ms = tonumber(oldest[2]) + window_ms end " +
            "return {allowed, remaining, reset_ms}"
        );
        return script;
    }
}
