package com.meterly.ingestion.ingest;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Request bodies for the ingestion endpoints; field constraints mirror {@code docs/api-contract.md} §2
 * exactly. {@code Batch.events} deliberately has no {@code @Valid} cascade: the controller validates each
 * element manually so one malformed item can be reported as a {@code rejected} result instead of failing
 * the whole batch with a 400.
 */
public final class IngestionRequests {

    public static final int MAX_BATCH_SIZE = 100;

    private IngestionRequests() {
    }

    public record UsageEvent(
            @NotNull UUID resourceId,
            @NotBlank @Size(min = 1, max = 255) @Pattern(regexp = "[A-Za-z0-9._:\\-]+") String idempotencyKey,
            @NotNull @Min(1) @Max(1_000_000_000) Long quantity,
            Instant occurredAt) {
    }

    public record Batch(@NotNull @Size(min = 1, max = MAX_BATCH_SIZE) List<@NotNull UsageEvent> events) {
    }
}
