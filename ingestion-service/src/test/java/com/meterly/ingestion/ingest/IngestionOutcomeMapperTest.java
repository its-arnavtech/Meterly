package com.meterly.ingestion.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meterly.common.web.ApiException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Unit tests for the exhaustive {@link IngestionOutcome} to HTTP-shape mapping. */
class IngestionOutcomeMapperTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-09-21T17:04:05.123Z");

    @Test
    void acceptedBecomes202WithAcceptedStatus() {
        var outcome = new IngestionOutcome.Accepted("key-1", RECEIVED_AT);

        ResponseEntity<Object> response = IngestionOutcomeMapper.toResponseEntity(outcome);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        var body = (IngestionResponses.Accepted) response.getBody();
        assertThat(body.status()).isEqualTo("accepted");
        assertThat(body.idempotencyKey()).isEqualTo("key-1");
        assertThat(body.receivedAt()).isEqualTo(RECEIVED_AT);
    }

    @Test
    void duplicateBecomes200WithDuplicateStatus() {
        var outcome = new IngestionOutcome.Duplicate("key-2");

        ResponseEntity<Object> response = IngestionOutcomeMapper.toResponseEntity(outcome);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = (IngestionResponses.Duplicate) response.getBody();
        assertThat(body.status()).isEqualTo("duplicate");
        assertThat(body.idempotencyKey()).isEqualTo("key-2");
    }

    @Test
    void rejectedIsThrownAsItsApiException() {
        var outcome = new IngestionOutcome.Rejected("key-3", HttpStatus.UNPROCESSABLE_ENTITY,
                "UNKNOWN_RESOURCE", "resource_id is not an active resource of this tenant");

        assertThatThrownBy(() -> IngestionOutcomeMapper.toResponseEntity(outcome))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.code()).isEqualTo("UNKNOWN_RESOURCE");
                });
    }

    @Test
    void batchItemsCarryStatusAndOmitCodeForSuccesses() {
        assertThat(IngestionOutcomeMapper.toBatchItem(new IngestionOutcome.Accepted("key-4", RECEIVED_AT)))
                .isEqualTo(IngestionResponses.BatchItem.accepted("key-4"));
        assertThat(IngestionOutcomeMapper.toBatchItem(new IngestionOutcome.Duplicate("key-5")))
                .isEqualTo(IngestionResponses.BatchItem.duplicate("key-5"));
    }

    @Test
    void batchItemForRejectionCarriesCodeAndDetail() {
        var outcome = new IngestionOutcome.Rejected("key-6", HttpStatus.CONFLICT,
                "IDEMPOTENCY_KEY_REUSED", "This idempotency key was already used with a different payload");

        IngestionResponses.BatchItem item = IngestionOutcomeMapper.toBatchItem(outcome);

        assertThat(item).isEqualTo(IngestionResponses.BatchItem.rejected(
                "key-6", "IDEMPOTENCY_KEY_REUSED", "This idempotency key was already used with a different payload"));
    }
}
