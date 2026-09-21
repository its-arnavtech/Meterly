package com.meterly.common.redis;

import com.meterly.common.billing.BillingPeriod;
import com.meterly.common.crypto.Hashing;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

/**
 * Every Redis key and channel used by Meterly, in one place. Redis is a cache and coordination store
 * only; PostgreSQL is the system of record for usage and billing.
 */
public final class RedisKeys {

    /** Pub/sub channel carrying {@link com.meterly.common.event.UsageUpdate} JSON between metering instances. */
    public static final String USAGE_UPDATES_CHANNEL = "meterly:usage-updates";

    /** Counters outlive their period long enough for the post-close reconciliation and billing preview. */
    public static final Duration USAGE_COUNTER_TTL = Duration.ofDays(70);
    public static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    public static final Duration THRESHOLD_MARKER_TTL = Duration.ofDays(70);

    private RedisKeys() {
    }

    /** Open-period usage counter (string holding an integer): {@code meterly:usage:{tenant}:{resource}:{yyyy-MM}}. */
    public static String usageCounter(UUID tenantId, UUID resourceId, BillingPeriod period) {
        return "meterly:usage:" + tenantId + ":" + resourceId + ":" + period.yearMonth();
    }

    /** Token-bucket state (hash with {@code tokens}, {@code ts}) for per-tenant ingestion rate limiting. */
    public static String ingestRateLimit(UUID tenantId) {
        return "meterly:ratelimit:ingest:" + tenantId;
    }

    /** Ingestion-side idempotency record; the key is hashed to bound its length. Value: payload fingerprint. */
    public static String idempotency(UUID tenantId, String idempotencyKey) {
        return "meterly:idem:" + tenantId + ":" + Hashing.sha256Hex(idempotencyKey);
    }

    /** Set-once marker so each usage threshold notification fires once per resource per period. */
    public static String thresholdMarker(UUID tenantId, UUID resourceId, BillingPeriod period, int percent) {
        return "meterly:threshold:" + tenantId + ":" + resourceId + ":" + period.yearMonth() + ":" + percent;
    }

    /** Token bucket for login / signup throttling; {@code subject} is e.g. an IP or lower-cased email. */
    public static String authThrottle(String scope, String subject) {
        return "meterly:throttle:" + scope + ":" + Hashing.sha256Hex(subject.toLowerCase(Locale.ROOT));
    }
}
