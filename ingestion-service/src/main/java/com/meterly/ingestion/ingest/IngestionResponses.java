package com.meterly.ingestion.ingest;

import java.time.Instant;
import java.util.List;

/** Response bodies for the ingestion endpoints, matching {@code docs/api-contract.md} §2. */
public final class IngestionResponses {

    private IngestionResponses() {
    }

    public record Accepted(String status, String idempotencyKey, Instant receivedAt) {
        public Accepted(String idempotencyKey, Instant receivedAt) {
            this("accepted", idempotencyKey, receivedAt);
        }
    }

    public record Duplicate(String status, String idempotencyKey) {
        public Duplicate(String idempotencyKey) {
            this("duplicate", idempotencyKey);
        }
    }

    /** One row of a batch result; {@code code}/{@code detail} are null unless {@code status} is rejected. */
    public record BatchItem(String idempotencyKey, String status, String code, String detail) {
        public static BatchItem accepted(String idempotencyKey) {
            return new BatchItem(idempotencyKey, "accepted", null, null);
        }

        public static BatchItem duplicate(String idempotencyKey) {
            return new BatchItem(idempotencyKey, "duplicate", null, null);
        }

        public static BatchItem rejected(String idempotencyKey, String code, String detail) {
            return new BatchItem(idempotencyKey, "rejected", code, detail);
        }
    }

    public record BatchResult(List<BatchItem> results) {
    }
}
