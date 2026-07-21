package com.meterly.billing.metering;

import java.time.Instant;
import java.util.UUID;

public record UsageUpdate(UUID tenantId, UUID resourceId, long totalUnits, Instant publishedAt) {
}

