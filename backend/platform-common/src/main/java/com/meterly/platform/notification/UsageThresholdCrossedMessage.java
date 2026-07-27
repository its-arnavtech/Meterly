package com.meterly.platform.notification;

import java.time.Instant;
import java.util.UUID;

public record UsageThresholdCrossedMessage(
        UUID eventId,
        UUID tenantId,
        UUID resourceId,
        long totalUnits,
        long threshold,
        Instant occurredAt
) { }
