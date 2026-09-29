package com.meterly.ingestion.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Field-level boundaries from contract §2: idempotency_key length/charset, quantity 1..1e9. */
class IngestionRequestsValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private IngestionRequests.UsageEvent eventWith(String idempotencyKey, Long quantity) {
        return new IngestionRequests.UsageEvent(UUID.randomUUID(), idempotencyKey, quantity, null);
    }

    @Test
    void quantityOfOneIsValid() {
        assertThat(validator.validate(eventWith("req-1", 1L))).isEmpty();
    }

    @Test
    void quantityOfZeroIsInvalid() {
        assertThat(violationFields(eventWith("req-1", 0L))).contains("quantity");
    }

    @Test
    void quantityAtTheUpperBoundIsValid() {
        assertThat(validator.validate(eventWith("req-1", 1_000_000_000L))).isEmpty();
    }

    @Test
    void quantityOneOverTheUpperBoundIsInvalid() {
        assertThat(violationFields(eventWith("req-1", 1_000_000_001L))).contains("quantity");
    }

    @Test
    void quantityMustNotBeNull() {
        assertThat(violationFields(eventWith("req-1", null))).contains("quantity");
    }

    @Test
    void idempotencyKeyOfMaxLengthIsValid() {
        String key = "a".repeat(255);
        assertThat(validator.validate(eventWith(key, 1L))).isEmpty();
    }

    @Test
    void idempotencyKeyOneOverMaxLengthIsInvalid() {
        String key = "a".repeat(256);
        assertThat(violationFields(eventWith(key, 1L))).contains("idempotencyKey");
    }

    @Test
    void blankIdempotencyKeyIsInvalid() {
        assertThat(violationFields(eventWith("", 1L))).contains("idempotencyKey");
    }

    @Test
    void idempotencyKeyWithASpaceIsInvalid() {
        assertThat(violationFields(eventWith("has space", 1L))).contains("idempotencyKey");
    }

    @Test
    void idempotencyKeyAllowsTheDocumentedCharacterSet() {
        assertThat(validator.validate(eventWith("req_7f3a.colon:dash-9", 1L))).isEmpty();
    }

    @Test
    void resourceIdMustNotBeNull() {
        var event = new IngestionRequests.UsageEvent(null, "req-1", 1L, null);
        assertThat(violationFields(event)).contains("resourceId");
    }

    @Test
    void occurredAtIsOptional() {
        var event = new IngestionRequests.UsageEvent(UUID.randomUUID(), "req-1", 1L, Instant.now());
        assertThat(validator.validate(event)).isEmpty();
    }

    private Set<String> violationFields(IngestionRequests.UsageEvent event) {
        Set<ConstraintViolation<IngestionRequests.UsageEvent>> violations = validator.validate(event);
        assertThat(violations).isNotEmpty();
        return violations.stream().map(v -> v.getPropertyPath().toString()).collect(java.util.stream.Collectors.toSet());
    }
}
