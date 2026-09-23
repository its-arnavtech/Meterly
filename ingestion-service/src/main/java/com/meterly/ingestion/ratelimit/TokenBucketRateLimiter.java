package com.meterly.ingestion.ratelimit;

import com.meterly.common.redis.RedisKeys;
import com.meterly.ingestion.config.IngestionProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Per-tenant token-bucket rate limiting, implemented as a single atomic Lua script so concurrent
 * requests for the same tenant never race on read-modify-write of the bucket state. A batch consumes
 * {@code cost} tokens all-or-nothing: the script only subtracts when the full cost is available.
 *
 * <p>Fails open on a Redis error: the request is allowed and a counter is incremented, since an
 * unavailable rate limiter must never itself block ingestion.
 */
@Component
public class TokenBucketRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(TokenBucketRateLimiter.class);

    private static final String SCRIPT_BODY = """
            local time = redis.call('TIME')
            local now = tonumber(time[1]) + tonumber(time[2]) / 1000000
            local capacity = tonumber(ARGV[1])
            local refill_per_second = tonumber(ARGV[2])
            local cost = tonumber(ARGV[3])
            local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
            local tokens = tonumber(state[1])
            if tokens == nil then
                tokens = capacity
            end
            local last = tonumber(state[2])
            if last == nil then
                last = now
            end
            local elapsed = math.max(0, now - last)
            tokens = math.min(capacity, tokens + elapsed * refill_per_second)
            local allowed = 0
            local retry_after = 0
            if tokens >= cost then
                tokens = tokens - cost
                allowed = 1
            else
                retry_after = math.max(1, math.ceil((cost - tokens) / refill_per_second))
            end
            redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', now)
            redis.call('EXPIRE', KEYS[1], math.ceil(capacity / refill_per_second) + 60)
            return {allowed, math.floor(tokens), retry_after}
            """;

    private static final DefaultRedisScript<List> SCRIPT = new DefaultRedisScript<>(SCRIPT_BODY, List.class);

    private final StringRedisTemplate redis;
    private final MeterRegistry metrics;
    private final IngestionProperties properties;

    public TokenBucketRateLimiter(StringRedisTemplate redis, MeterRegistry metrics, IngestionProperties properties) {
        this.redis = redis;
        this.metrics = metrics;
        this.properties = properties;
    }

    public RateLimitDecision consume(UUID tenantId, int cost) {
        long capacity = properties.rateLimit().capacity();
        long refillPerSecond = properties.rateLimit().refillPerSecond();
        try {
            List<?> result = redis.execute(SCRIPT, List.of(RedisKeys.ingestRateLimit(tenantId)),
                    String.valueOf(capacity), String.valueOf(refillPerSecond), String.valueOf(cost));
            boolean allowed = ((Number) result.get(0)).longValue() == 1;
            long remaining = ((Number) result.get(1)).longValue();
            long retryAfter = ((Number) result.get(2)).longValue();
            return new RateLimitDecision(allowed, capacity, remaining, retryAfter);
        } catch (DataAccessException e) {
            log.warn("Rate limiter Redis call failed for tenant {}; failing open", tenantId, e);
            metrics.counter("meterly.ingestion.rate_limit.fail_open").increment();
            return new RateLimitDecision(true, capacity, capacity, 0);
        }
    }
}
