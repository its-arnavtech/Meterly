package com.meterly.metering.counter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.meterly.common.billing.BillingPeriod;
import com.meterly.common.event.UsageUpdate;
import com.meterly.metering.MeteringMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * Redis is a cache, not a system of record: when it is unavailable the projection must degrade to a
 * counted warning instead of throwing, because the ledger row is already committed and an exception
 * here would make Kafka redeliver a record that changed nothing.
 */
class UsageCounterCacheTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final UsageCounterCache cache = new UsageCounterCache(redis,
            new ObjectMapper().registerModule(new JavaTimeModule()), new MeteringMetrics(registry));

    private final CounterKey key = new CounterKey(UUID.randomUUID(), UUID.randomUUID(),
            BillingPeriod.of(YearMonth.of(2026, 9)));

    @Test
    void projectionFailureIsCountedAndSwallowed() {
        when(redis.execute(any(), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("redis is down"));

        UsageUpdate update = new UsageUpdate(key.tenantId(), key.resourceId(), key.period().start(), 5, 5,
                Instant.parse("2026-09-21T10:00:00Z"), Instant.parse("2026-09-21T10:00:01Z"));

        assertThat(cache.project(key, new CounterSnapshot(5, 1), update)).isEqualTo(UsageCounterCache.Projection.FAILED);
        assertThat(registry.get(MeteringMetrics.REDIS_FAILURES).tag("operation", "project").counter().count())
                .isEqualTo(1);
    }

    @Test
    void readFailureLooksLikeAnUnavailableCacheAndAMissingTotal() {
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.multiGet(anyList())).thenThrow(new RedisConnectionFailureException("redis is down"));

        assertThat(cache.read(key)).isInstanceOf(UsageCounterCache.Cached.Unavailable.class);
        assertThat(cache.readTotals(key.tenantId(), key.period(), List.of(key.resourceId()))).isEmpty();
        assertThat(registry.get(MeteringMetrics.REDIS_FAILURES).tag("operation", "read").counter().count())
                .isEqualTo(2);
    }

    @Test
    void unparseableCounterValueCountsAsAMiss() {
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.multiGet(anyList()))
                .thenReturn(List.of("not-a-number", "7"))
                .thenReturn(List.of("not-a-number"));

        assertThat(cache.read(key)).isInstanceOf(UsageCounterCache.Cached.Miss.class);
        assertThat(cache.readTotals(key.tenantId(), key.period(), List.of(key.resourceId()))).isEmpty();
    }
}
