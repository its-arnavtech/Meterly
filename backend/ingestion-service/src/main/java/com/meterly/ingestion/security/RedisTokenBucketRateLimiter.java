package com.meterly.ingestion.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Component
public class RedisTokenBucketRateLimiter implements TenantRateLimiter {
    private static final DefaultRedisScript<List> CONSUME_SCRIPT = new DefaultRedisScript<>("""
            local values = redis.call('HMGET', KEYS[1], 'tokens', 'updated_at')
            local tokens = tonumber(values[1])
            local updated_at = tonumber(values[2])
            local capacity = tonumber(ARGV[1])
            local refill = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            if tokens == nil then tokens = capacity end
            if updated_at == nil then updated_at = now end
            local elapsed = math.max(0, now - updated_at) / 1000
            tokens = math.min(capacity, tokens + (elapsed * refill))
            local allowed = 0
            local retry_after = 0
            if tokens >= 1 then
              tokens = tokens - 1
              allowed = 1
            else
              retry_after = math.ceil((1 - tokens) / refill)
            end
            redis.call('HSET', KEYS[1], 'tokens', tokens, 'updated_at', now)
            redis.call('EXPIRE', KEYS[1], math.max(60, math.ceil((capacity / refill) * 2)))
            return {allowed, retry_after}
            """, List.class);

    private final StringRedisTemplate redisTemplate;
    private final Clock clock;
    private final int capacity;
    private final double refillPerSecond;

    public RedisTokenBucketRateLimiter(
            StringRedisTemplate redisTemplate,
            @Value("${meterly.rate-limit.capacity:20}") int capacity,
            @Value("${meterly.rate-limit.refill-per-second:10}") double refillPerSecond
    ) {
        if (capacity < 1 || refillPerSecond <= 0) {
            throw new IllegalArgumentException("Rate-limit capacity and refill rate must be positive");
        }
        this.redisTemplate = redisTemplate;
        this.clock = Clock.systemUTC();
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
    }

    @Override
    public Decision consume(UUID tenantId) {
        List<?> result = redisTemplate.execute(
                CONSUME_SCRIPT,
                List.of("rate-limit:usage:" + tenantId),
                Integer.toString(capacity),
                Double.toString(refillPerSecond),
                Long.toString(clock.millis())
        );
        if (result == null || result.size() != 2) {
            throw new IllegalStateException("Redis rate-limit script returned no decision");
        }
        boolean allowed = ((Number) result.get(0)).longValue() == 1;
        long retryAfter = ((Number) result.get(1)).longValue();
        return allowed ? Decision.granted() : Decision.rejected(retryAfter);
    }
}
