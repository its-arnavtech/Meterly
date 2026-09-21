package com.meterly.ingestion;

import com.meterly.common.redis.RedisKeys;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
class IngestionRateLimiter {
    private static final DefaultRedisScript<List> SCRIPT = new DefaultRedisScript<>("""
        local t = redis.call('TIME')
        local now = tonumber(t[1]) + tonumber(t[2]) / 1000000
        local cap, rate, cost = tonumber(ARGV[1]), tonumber(ARGV[2]), tonumber(ARGV[3])
        local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
        local tokens = math.min(cap, tonumber(state[1]) or cap)
        tokens = math.min(cap, tokens + math.max(0, now - (tonumber(state[2]) or now)) * rate)
        local allowed, retry = 0, math.max(1, math.ceil((cost - tokens) / rate))
        if tokens >= cost then tokens = tokens - cost; allowed = 1; retry = 0 end
        redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', now)
        redis.call('EXPIRE', KEYS[1], math.ceil(cap / rate) + 60)
        return {allowed, math.floor(tokens), retry}
        """, List.class);
    record Decision(boolean allowed, long remaining, long retryAfter, long limit) {}
    private final StringRedisTemplate redis;
    private final MeterRegistry metrics;
    private final long capacity;
    private final long rate;
    IngestionRateLimiter(StringRedisTemplate redis, MeterRegistry metrics,
            @Value("${meterly.ingestion.rate-limit.capacity:200}") long capacity,
            @Value("${meterly.ingestion.rate-limit.tokens-per-second:100}") long rate) {
        if (capacity < 100 || rate < 1) throw new IllegalArgumentException("Rate limit capacity must be >=100 and refill positive");
        this.redis = redis; this.metrics = metrics; this.capacity = capacity; this.rate = rate;
    }
    Decision consume(UUID tenant, int count) {
        try {
            List<?> result = redis.execute(SCRIPT, List.of(RedisKeys.ingestRateLimit(tenant)), "" + capacity, "" + rate, "" + count);
            return new Decision(((Number)result.get(0)).longValue() == 1, ((Number)result.get(1)).longValue(), ((Number)result.get(2)).longValue(), capacity);
        } catch (org.springframework.data.redis.RedisConnectionFailureException | org.springframework.dao.QueryTimeoutException ex) {
            metrics.counter("meterly_ingestion_rate_limit_fail_open_total").increment();
            return new Decision(true, capacity, 0, capacity);
        }
    }
}
