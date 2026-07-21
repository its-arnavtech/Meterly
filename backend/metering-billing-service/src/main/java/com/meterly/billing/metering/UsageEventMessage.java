package com.meterly.billing.metering;

import java.time.Instant;
import java.util.UUID;

public record UsageEventMessage(
        UUID tenantId,
        UUID resourceId,
        UUID idempotencyKey,
        long quantity,
        Instant occurredAt
) {
    public UsageEventMessage {
        if (tenantId == null || resourceId == null || idempotencyKey == null || occurredAt == null) {
            throw new IllegalArgumentException("Usage event identifiers and occurredAt are required");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("Usage quantity must be positive");
        }
    }
}

