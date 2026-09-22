package com.meterly.metering.counter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meterly.common.billing.BillingPeriod;
import com.meterly.common.event.UsageUpdate;
import com.meterly.common.redis.RedisKeys;
import com.meterly.metering.MeteringMetrics;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis copy of the usage counters, for fast dashboard reads and live fan-out. Redis is disposable:
 * every method degrades gracefully (WARN + {@code meterly.metering.redis_failures}) and never throws, so
 * a Redis outage can neither fail a committed Kafka record nor break a read; the database projection and
 * the nightly reconciliation restore it.
 */
@Component
public class UsageCounterCache {

    /** Result of writing a snapshot to Redis. */
    public enum Projection {
        /** The snapshot was written (and the update, if any, published). */
        APPLIED,
        /** Redis already holds this or a newer version; nothing changed. */
        STALE,
        /** Redis was unavailable. */
        FAILED
    }

    /** What Redis holds for one counter. */
    public sealed interface Cached {

        record Hit(long totalUnits, long ledgerVersion) implements Cached {
        }

        record Miss() implements Cached {
        }

        record Unavailable() implements Cached {
        }
    }

    /**
     * Writes counter + version (both with the counter TTL) and optionally publishes an update, atomically.
     * In {@code guarded} mode the write happens only if the incoming version is newer than the stored one
     * (or equal while the value key is missing), so a late or replayed projection can never move a counter
     * backwards. {@code force} mode (reconciliation, which holds the counter lock) always writes. Versions
     * are compared as decimal strings (length first), which is exact for any bigint, unlike Lua numbers.
     * The update is published only when the write happened, so subscribers see totals in version order.
     *
     * <p>KEYS: counter, version. ARGV: total, version, ttl seconds, mode, channel, payload ('' = none).
     */
    private static final RedisScript<Long> PROJECT_SCRIPT = RedisScript.of("""
            local function newer(a, b)
              if #a ~= #b then return #a > #b end
              return a > b
            end
            local stored = redis.call('GET', KEYS[2])
            if ARGV[4] ~= 'force' and stored then
              if newer(stored, ARGV[2]) then return 0 end
              if stored == ARGV[2] and redis.call('EXISTS', KEYS[1]) == 1 then return 0 end
            end
            redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[3])
            redis.call('SET', KEYS[2], ARGV[2], 'EX', ARGV[3])
            if ARGV[6] ~= '' then redis.call('PUBLISH', ARGV[5], ARGV[6]) end
            return 1
            """, Long.class);

    private static final Logger log = LoggerFactory.getLogger(UsageCounterCache.class);
    private static final String TTL_SECONDS = Long.toString(RedisKeys.USAGE_COUNTER_TTL.toSeconds());

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final MeteringMetrics metrics;

    public UsageCounterCache(StringRedisTemplate redis, ObjectMapper objectMapper, MeteringMetrics metrics) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    /** Version-guarded write; publishes {@code update} on {@link RedisKeys#USAGE_UPDATES_CHANNEL} if applied. */
    public Projection project(CounterKey key, CounterSnapshot snapshot, UsageUpdate update) {
        String payload;
        try {
            payload = update == null ? "" : objectMapper.writeValueAsString(update);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("UsageUpdate is not serializable", e);
        }
        return write(key, snapshot, "guarded", payload, "project");
    }

    /** Unconditional write, for reconciliation while it holds the counter's advisory lock. */
    public Projection repair(CounterKey key, CounterSnapshot snapshot) {
        return write(key, snapshot, "force", "", "repair");
    }

    /** Reads counter and version of one counter. */
    public Cached read(CounterKey key) {
        try {
            List<String> values = redis.opsForValue().multiGet(List.of(key.redisKey(), key.redisVersionKey()));
            if (values == null || values.get(0) == null || values.get(1) == null) {
                return new Cached.Miss();
            }
            return new Cached.Hit(Long.parseLong(values.get(0)), Long.parseLong(values.get(1)));
        } catch (NumberFormatException e) {
            log.warn("Unparseable usage counter in Redis for key {}; treating it as a miss", key.redisKey());
            return new Cached.Miss();
        } catch (RuntimeException e) {
            failed("read", e);
            return new Cached.Unavailable();
        }
    }

    /**
     * Reads the totals of several counters of one tenant and period in one round trip. Missing or
     * unparseable values are absent from the result; if Redis is unavailable the result is empty.
     */
    public Map<UUID, Long> readTotals(UUID tenantId, BillingPeriod period, List<UUID> resourceIds) {
        Map<UUID, Long> totals = new HashMap<>();
        if (resourceIds.isEmpty()) {
            return totals;
        }
        List<String> keys = new ArrayList<>(resourceIds.size());
        for (UUID resourceId : resourceIds) {
            keys.add(RedisKeys.usageCounter(tenantId, resourceId, period));
        }
        List<String> values;
        try {
            values = redis.opsForValue().multiGet(keys);
        } catch (RuntimeException e) {
            failed("read", e);
            return totals;
        }
        for (int i = 0; values != null && i < values.size(); i++) {
            String value = values.get(i);
            if (value == null) {
                continue;
            }
            try {
                totals.put(resourceIds.get(i), Long.parseLong(value));
            } catch (NumberFormatException e) {
                log.warn("Unparseable usage counter in Redis for key {}; treating it as a miss", keys.get(i));
            }
        }
        return totals;
    }

    private Projection write(CounterKey key, CounterSnapshot snapshot, String mode, String payload, String operation) {
        try {
            Long applied = redis.execute(PROJECT_SCRIPT,
                    List.of(key.redisKey(), key.redisVersionKey()),
                    Long.toString(snapshot.totalUnits()),
                    Long.toString(snapshot.ledgerVersion()),
                    TTL_SECONDS,
                    mode,
                    RedisKeys.USAGE_UPDATES_CHANNEL,
                    payload);
            return applied != null && applied == 1L ? Projection.APPLIED : Projection.STALE;
        } catch (RuntimeException e) {
            failed(operation, e);
            return Projection.FAILED;
        }
    }

    private void failed(String operation, RuntimeException e) {
        metrics.redisFailure(operation);
        log.warn("Redis {} failed ({}); PostgreSQL remains authoritative and reconciliation will repair the cache",
                operation, e.getClass().getSimpleName());
    }
}
