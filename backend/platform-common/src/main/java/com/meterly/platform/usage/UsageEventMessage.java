package com.meterly.platform.usage;

import java.time.Instant;
import java.util.UUID;

public record UsageEventMessage(
        UUID tenantId,
        UUID resourceId,
        UUID idempotencyKey,
        long quantity,
        Instant occurredAt
) {
}
