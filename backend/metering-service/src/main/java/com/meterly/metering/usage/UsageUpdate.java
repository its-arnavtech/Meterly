package com.meterly.metering.usage;

import java.time.Instant;
import java.util.UUID;

public record UsageUpdate(UUID tenantId, UUID resourceId, long totalUnits, Instant updatedAt) {
}
