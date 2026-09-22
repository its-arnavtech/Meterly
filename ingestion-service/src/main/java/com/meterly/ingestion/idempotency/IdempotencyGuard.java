package com.meterly.ingestion.idempotency;

import com.meterly.common.crypto.Hashing;
import com.meterly.common.redis.RedisKeys;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * The ingestion-side idempotency fast path (Redis, 24h). This is a convenience, not the source of
 * truth: correctness never depends on it. The real guarantee is metering-service's
 * {@code (tenant_id, idempotency_key)} unique constraint on the usage-event ledger, so a redelivered or
 * concurrently-retried Kafka record is still counted at most once even if this Redis check is skipped
 * (fail-open) or lost (a crash between the Kafka ack and the {@code SET NX} below). This class only
 * saves a redundant publish and lets a legitimate retry get an immediate {@code duplicate} response.
 */
@Component
public class IdempotencyGuard {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyGuard.class);

    private final StringRedisTemplate redis;
    private final MeterRegistry metrics;

    public IdempotencyGuard(StringRedisTemplate redis, MeterRegistry metrics) {
        this.redis = redis;
        this.metrics = metrics;
    }

    /**
     * Fingerprints the client-supplied fields that define "the same request". {@code occurredAtAsSent}
     * is fingerprinted exactly as the client sent it (or the literal {@code "absent"} when omitted), not
     * as resolved against "now" — otherwise two retries of the same omitted-timestamp request could
     * fingerprint differently depending on when each arrived.
     */
    public String fingerprint(UUID resourceId, long quantity, Instant occurredAtAsSent) {
        String occurredAtPart = occurredAtAsSent == null ? "absent" : occurredAtAsSent.toString();
        return Hashing.sha256Hex(resourceId + "|" + quantity + "|" + occurredAtPart);
    }

    /** Looks up a previously recorded fingerprint for this idempotency key. Fails open (empty) on a
     * Redis error, incrementing a counter, so an outage never blocks ingestion. */
    public Optional<String> lookup(UUID tenantId, String idempotencyKey) {
        try {
            return Optional.ofNullable(redis.opsForValue().get(RedisKeys.idempotency(tenantId, idempotencyKey)));
        } catch (DataAccessException e) {
            log.warn("Idempotency lookup failed for tenant {}; failing open", tenantId, e);
            metrics.counter("meterly.ingestion.redis.fail_open", "component", "idempotency").increment();
            return Optional.empty();
        }
    }

    /** Records the fingerprint only after the Kafka publish is acknowledged, so a failed publish never
     * leaves a false "already accepted" marker behind. Uses {@code SET NX}; fails open on a Redis error
     * since the event has already been durably published regardless. */
    public void record(UUID tenantId, String idempotencyKey, String fingerprint) {
        try {
            redis.opsForValue().setIfAbsent(
                    RedisKeys.idempotency(tenantId, idempotencyKey), fingerprint, RedisKeys.IDEMPOTENCY_TTL);
        } catch (DataAccessException e) {
            log.warn("Idempotency record failed for tenant {}; relying on the metering consumer's "
                    + "unique constraint for correctness", tenantId, e);
            metrics.counter("meterly.ingestion.redis.fail_open", "component", "idempotency").increment();
        }
    }
}
