package com.meterly.ingestion.ingest;

import com.meterly.common.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Maps a resolved {@link IngestionOutcome} to the shape each endpoint needs. Kept separate from
 * {@link IngestionController} so the mapping itself — including the exhaustive switches — is unit
 * testable without a Spring context.
 */
final class IngestionOutcomeMapper {

    private IngestionOutcomeMapper() {
    }

    /** The single-event endpoint has no room in its response shape for a rejection, so a
     * {@link IngestionOutcome.Rejected} is thrown as the {@link ApiException} it represents instead. */
    static ResponseEntity<Object> toResponseEntity(IngestionOutcome outcome) {
        return switch (outcome) {
            case IngestionOutcome.Accepted accepted -> ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(new IngestionResponses.Accepted(accepted.idempotencyKey(), accepted.receivedAt()));
            case IngestionOutcome.Duplicate duplicate ->
                    ResponseEntity.ok(new IngestionResponses.Duplicate(duplicate.idempotencyKey()));
            case IngestionOutcome.Rejected rejected ->
                    throw new ApiException(rejected.status(), rejected.code(), rejected.detail());
        };
    }

    /** The batch endpoint reports every outcome, including a rejection, as one result row. */
    static IngestionResponses.BatchItem toBatchItem(IngestionOutcome outcome) {
        return switch (outcome) {
            case IngestionOutcome.Accepted accepted -> IngestionResponses.BatchItem.accepted(accepted.idempotencyKey());
            case IngestionOutcome.Duplicate duplicate -> IngestionResponses.BatchItem.duplicate(duplicate.idempotencyKey());
            case IngestionOutcome.Rejected rejected ->
                    IngestionResponses.BatchItem.rejected(rejected.idempotencyKey(), rejected.code(), rejected.detail());
        };
    }
}
