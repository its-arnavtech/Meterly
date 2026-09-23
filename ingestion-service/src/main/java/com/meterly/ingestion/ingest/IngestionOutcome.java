package com.meterly.ingestion.ingest;

import java.time.Instant;
import org.springframework.http.HttpStatus;

/**
 * The result of ingesting one usage event. Used identically by the single-event and batch endpoints: the
 * single endpoint pattern-matches this into an HTTP response (throwing for {@link Rejected}), while the
 * batch endpoint maps every item — including rejections — into a {@code results[]} entry without
 * aborting the rest of the batch. Sealed and exhaustively switched, per {@code docs/why-java.md}: a
 * fourth outcome added later fails to compile everywhere it is not yet handled.
 */
public sealed interface IngestionOutcome {

    String idempotencyKey();

    record Accepted(String idempotencyKey, Instant receivedAt) implements IngestionOutcome {
    }

    record Duplicate(String idempotencyKey) implements IngestionOutcome {
    }

    record Rejected(String idempotencyKey, HttpStatus status, String code, String detail) implements IngestionOutcome {
    }
}
