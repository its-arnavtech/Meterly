package com.meterly.tenant.throttle;

import com.meterly.tenant.config.TenantProperties;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Token bucket evaluated atomically inside Redis by a Lua script, so concurrent requests across any number
 * of tenant-service instances share one consistent bucket. The script uses the Redis server clock
 * ({@code TIME}), so instance clock skew cannot refill a bucket early. Idle buckets expire once full.
 */
@Component
public class RedisTokenBucket {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenBucket.class);

    /**
     * KEYS[1] bucket hash; ARGV[1] capacity; ARGV[2] refill rate in tokens per second.
     * Returns {allowed (1|0), seconds until the next token when not allowed}.
     */
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> TAKE_TOKEN = RedisScript.of("""
            local capacity = tonumber(ARGV[1])
            local rate = tonumber(ARGV[2])
            local clock = redis.call('TIME')
            local now = tonumber(clock[1]) + tonumber(clock[2]) / 1000000
            local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
            local tokens = tonumber(state[1])
            local updated = tonumber(state[2])
            if tokens == nil or updated == nil then
              tokens = capacity
              updated = now
            end
            tokens = math.min(capacity, tokens + math.max(0, now - updated) * rate)
            local allowed = 0
            local retry_after = 0
            if tokens >= 1 then
              tokens = tokens - 1
              allowed = 1
            else
              retry_after = math.ceil((1 - tokens) / rate)
            end
            redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(now))
            redis.call('EXPIRE', KEYS[1], math.ceil(capacity / rate) + 1)
            return {allowed, retry_after}
            """, List.class);

    private final StringRedisTemplate redis;

    public RedisTokenBucket(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public ThrottleDecision take(String key, TenantProperties.Bucket bucket) {
        List<?> result;
        try {
            result = redis.execute(TAKE_TOKEN, List.of(key),
                    Integer.toString(bucket.capacity()), Double.toString(bucket.refillPerSecond()));
        } catch (RuntimeException e) {
            log.warn("Auth throttle unavailable, failing open: {}", e.getClass().getSimpleName());
            return new ThrottleDecision.Unavailable();
        }
        if (result == null || result.size() != 2) {
            log.warn("Auth throttle returned an unexpected result, failing open");
            return new ThrottleDecision.Unavailable();
        }
        long allowed = ((Number) result.get(0)).longValue();
        long retryAfter = ((Number) result.get(1)).longValue();
        return allowed == 1 ? new ThrottleDecision.Allowed() : new ThrottleDecision.Limited(retryAfter);
    }
}
