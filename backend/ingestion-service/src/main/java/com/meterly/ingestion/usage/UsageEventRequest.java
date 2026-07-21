package com.meterly.ingestion.usage;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;
import java.util.UUID;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record UsageEventRequest(
        UUID resourceId,
        @NotNull UUID idempotencyKey,
        @Positive long quantity,
        Instant occurredAt
) {
}

