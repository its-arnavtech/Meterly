package com.meterly.common.event;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A usage event on the {@value Topics#USAGE_EVENTS} topic. Immutable, and equal by value, so a
 * redelivered record is indistinguishable from the original; the consumer relies on the
 * {@code (tenant_id, idempotency_key)} unique constraint, not on Kafka, for exactly-once counting.
 *
 * @param idempotencyKey client-generated key, unique per tenant
 * @param tenantId       tenant resolved from the authenticated API key / JWT, never from the body
 * @param resourceId     metered resource (must belong to the tenant; enforced by a composite FK)
 * @param quantity       units consumed, strictly positive
 * @param occurredAt     when the usage happened (client clock, validated at ingestion)
 * @param receivedAt     when ingestion-service accepted it
 * @param requestId      correlation ID of the ingesting HTTP request
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record UsageEventMessage(
        String idempotencyKey,
        UUID tenantId,
        UUID resourceId,
        long quantity,
        Instant occurredAt,
        Instant receivedAt,
        String requestId) {

    public static final int MAX_IDEMPOTENCY_KEY_LENGTH = 255;

    public UsageEventMessage {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(receivedAt, "receivedAt");
        if (idempotencyKey.isBlank() || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new IllegalArgumentException("idempotencyKey must be 1.." + MAX_IDEMPOTENCY_KEY_LENGTH + " chars");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
    }
}
