package com.meterly.metering.counter;

import com.meterly.common.billing.BillingPeriod;
import com.meterly.common.redis.RedisKeys;
import java.util.Objects;
import java.util.UUID;

/**
 * Identifies one usage counter: a tenant's resource in one billing period. It is the unit of locking
 * (advisory lock), of the transactional projection ({@code usage_counter_state}) and of the Redis cache.
 */
public record CounterKey(UUID tenantId, UUID resourceId, BillingPeriod period) {

    public CounterKey {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(period, "period");
    }

    /** Text hashed into the transaction-scoped advisory lock that serializes writers of this counter. */
    public String lockName() {
        return "usage-counter:" + tenantId + ":" + resourceId + ":" + period.start();
    }

    public String redisKey() {
        return RedisKeys.usageCounter(tenantId, resourceId, period);
    }

    /** Companion key holding the ledger version of the value in {@link #redisKey()}. */
    public String redisVersionKey() {
        return redisKey() + ":version";
    }
}
