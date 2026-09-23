package com.meterly.ingestion.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Unit tests for {@link IdempotencyGuard#fingerprint}: same client-supplied fields must fingerprint
 * identically (so a retry is recognized as a duplicate), and any difference — including only whether
 * occurred_at was supplied at all — must fingerprint differently (so it is treated as key reuse with a
 * different payload, not silently accepted as the same request).
 */
class IdempotencyGuardTest {

    private final IdempotencyGuard guard =
            new IdempotencyGuard(mock(StringRedisTemplate.class), new SimpleMeterRegistry());

    private final UUID resourceId = UUID.randomUUID();
    private final Instant occurredAt = Instant.parse("2026-09-21T17:04:05Z");

    @Test
    void identicalInputsFingerprintIdentically() {
        String first = guard.fingerprint(resourceId, 5L, occurredAt);
        String second = guard.fingerprint(resourceId, 5L, occurredAt);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void differentQuantityFingerprintsDifferently() {
        String withFive = guard.fingerprint(resourceId, 5L, occurredAt);
        String withSix = guard.fingerprint(resourceId, 6L, occurredAt);

        assertThat(withFive).isNotEqualTo(withSix);
    }

    @Test
    void differentResourceFingerprintsDifferently() {
        String first = guard.fingerprint(resourceId, 5L, occurredAt);
        String second = guard.fingerprint(UUID.randomUUID(), 5L, occurredAt);

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void differentOccurredAtFingerprintsDifferently() {
        String first = guard.fingerprint(resourceId, 5L, occurredAt);
        String second = guard.fingerprint(resourceId, 5L, occurredAt.plusSeconds(1));

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void omittedOccurredAtIsNotTheSameAsAnyExplicitTimestamp() {
        String omitted = guard.fingerprint(resourceId, 5L, null);
        String explicit = guard.fingerprint(resourceId, 5L, occurredAt);

        assertThat(omitted).isNotEqualTo(explicit);
    }

    @Test
    void omittingOccurredAtTwiceFingerprintsTheSameWayBothTimes() {
        String first = guard.fingerprint(resourceId, 5L, null);
        String second = guard.fingerprint(resourceId, 5L, null);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void fingerprintIsNotTheRawConcatenation() {
        String fingerprint = guard.fingerprint(resourceId, 5L, occurredAt);

        assertThat(fingerprint).doesNotContain(resourceId.toString());
        assertThat(fingerprint).hasSize(64);
    }
}
