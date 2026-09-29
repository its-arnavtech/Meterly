package com.meterly.ingestion.ingest;

import com.meterly.common.event.UsageEventMessage;
import com.meterly.common.web.CorrelationId;
import com.meterly.ingestion.config.IngestionProperties;
import com.meterly.ingestion.idempotency.IdempotencyGuard;
import com.meterly.ingestion.kafka.UsageEventPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Core per-event ingestion logic, shared by the single and batch endpoints. Never throws for a business
 * rejection (idempotency reuse, unknown resource, stale/future event): those become
 * {@link IngestionOutcome.Rejected} so the batch endpoint can report per-item results without aborting
 * the rest of the batch. Authentication and rate limiting are the caller's concern (they apply to the
 * request as a whole, not to one event) and are handled in {@link IngestionController} before this class
 * is invoked.
 *
 * <p>Checks run in this order deliberately: the idempotency fast path runs <em>first</em>, ahead of
 * resource/age validation. A retried request that was already accepted must keep succeeding even if,
 * say, its resource was archived or its {@code occurred_at} now falls outside the age window relative to
 * a later "now" — those fields were already validated the first time; only a genuinely new key runs the
 * full checks below.
 */
@Service
public class IngestionService {

    private final Clock clock;
    private final IngestionProperties properties;
    private final IdempotencyGuard idempotency;
    private final UsageEventPublisher publisher;
    private final MeterRegistry metrics;

    public IngestionService(Clock clock, IngestionProperties properties, IdempotencyGuard idempotency,
            UsageEventPublisher publisher, MeterRegistry metrics) {
        this.clock = clock;
        this.properties = properties;
        this.idempotency = idempotency;
        this.publisher = publisher;
        this.metrics = metrics;
    }

    public IngestionOutcome ingest(UUID tenantId, ResourceIdSource resources, IngestionRequests.UsageEvent event) {
        String fingerprint = idempotency.fingerprint(event.resourceId(), event.quantity(), event.occurredAt());
        Optional<String> existing = idempotency.lookup(tenantId, event.idempotencyKey());
        if (existing.isPresent()) {
            if (existing.get().equals(fingerprint)) {
                metrics.counter("meterly.ingestion.events", "outcome", "duplicate").increment();
                return new IngestionOutcome.Duplicate(event.idempotencyKey());
            }
            return reject(event.idempotencyKey(), HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                    "This idempotency key was already used with a different payload");
        }

        UUID resourceId = event.resourceId();
        if (!resources.contains(resourceId) && !resources.refreshContains(resourceId)) {
            return reject(event.idempotencyKey(), HttpStatus.UNPROCESSABLE_ENTITY, "UNKNOWN_RESOURCE",
                    "resource_id is not an active resource of this tenant");
        }

        Instant now = clock.instant();
        Instant occurredAt = event.occurredAt() == null ? now : event.occurredAt();
        if (occurredAt.isBefore(now.minus(properties.maxEventAge()))) {
            return reject(event.idempotencyKey(), HttpStatus.UNPROCESSABLE_ENTITY, "EVENT_TOO_OLD",
                    "occurred_at is older than the accepted event age window");
        }
        if (occurredAt.isAfter(now.plus(properties.maxClockSkew()))) {
            return reject(event.idempotencyKey(), HttpStatus.UNPROCESSABLE_ENTITY, "EVENT_IN_FUTURE",
                    "occurred_at is further in the future than the allowed clock skew");
        }

        String requestId = CorrelationId.current().orElseGet(() -> UUID.randomUUID().toString());
        UsageEventMessage message = new UsageEventMessage(
                event.idempotencyKey(), tenantId, resourceId, event.quantity(), occurredAt, now, requestId);
        publisher.publish(message);
        idempotency.record(tenantId, event.idempotencyKey(), fingerprint);
        metrics.counter("meterly.ingestion.events", "outcome", "accepted").increment();
        return new IngestionOutcome.Accepted(event.idempotencyKey(), now);
    }

    private IngestionOutcome.Rejected reject(String idempotencyKey, HttpStatus status, String code, String detail) {
        metrics.counter("meterly.ingestion.events", "outcome", "rejected").increment();
        return new IngestionOutcome.Rejected(idempotencyKey, status, code, detail);
    }
}
