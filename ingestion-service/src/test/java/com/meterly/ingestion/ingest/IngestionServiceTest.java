package com.meterly.ingestion.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.meterly.common.event.UsageEventMessage;
import com.meterly.ingestion.config.IngestionProperties;
import com.meterly.ingestion.idempotency.IdempotencyGuard;
import com.meterly.ingestion.kafka.UsageEventPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link IngestionService}: the occurred_at age/skew window at its exact boundaries (with
 * a fixed {@link Clock}, no sleeping), the idempotency fast path, and the unknown-resource refresh-once
 * behaviour. {@link IdempotencyGuard} and {@link UsageEventPublisher} are mocked since their own Redis
 * and Kafka behaviour is exercised in the integration tests.
 */
class IngestionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-21T17:00:00Z");
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID RESOURCE_ID = UUID.randomUUID();

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final IngestionProperties properties = new IngestionProperties(
            URI.create("http://localhost:8081"), Duration.ofSeconds(2), Duration.ofSeconds(3),
            Duration.ofHours(1), Duration.ofMinutes(5),
            new IngestionProperties.AuthCache(Duration.ofSeconds(60), Duration.ofSeconds(10)),
            Duration.ofSeconds(60), new IngestionProperties.RateLimit(500, 200), Duration.ofSeconds(5));

    private static final String DEFAULT_FINGERPRINT = "default-fingerprint";

    private IdempotencyGuard idempotency;
    private UsageEventPublisher publisher;
    private IngestionService service;
    private AlwaysContainsResourceIdSource resources;

    @BeforeEach
    void setUp() {
        idempotency = mock(IdempotencyGuard.class);
        publisher = mock(UsageEventPublisher.class);
        // The exact fingerprint value is IdempotencyGuard's own concern (see IdempotencyGuardTest);
        // here it only needs to be a stable value IngestionService can compare lookup() results against.
        when(idempotency.fingerprint(any(), anyLong(), any())).thenReturn(DEFAULT_FINGERPRINT);
        when(idempotency.lookup(any(), any())).thenReturn(Optional.empty());
        service = new IngestionService(clock, properties, idempotency, publisher, new SimpleMeterRegistry());
        resources = new AlwaysContainsResourceIdSource(true);
    }

    private IngestionRequests.UsageEvent event(String idempotencyKey, Instant occurredAt) {
        return new IngestionRequests.UsageEvent(RESOURCE_ID, idempotencyKey, 5L, occurredAt);
    }

    @Test
    void acceptsAndPublishesWhenNothingIsCached() {
        IngestionOutcome outcome = service.ingest(TENANT_ID, resources, event("key-1", NOW));

        assertThat(outcome).isInstanceOf(IngestionOutcome.Accepted.class);
        ArgumentCaptor<UsageEventMessage> captor = ArgumentCaptor.forClass(UsageEventMessage.class);
        verify(publisher).publish(captor.capture());
        assertThat(captor.getValue().tenantId()).isEqualTo(TENANT_ID);
        assertThat(captor.getValue().resourceId()).isEqualTo(RESOURCE_ID);
        assertThat(captor.getValue().quantity()).isEqualTo(5L);
        assertThat(captor.getValue().occurredAt()).isEqualTo(NOW);
        verify(idempotency).record(eq(TENANT_ID), eq("key-1"), any());
    }

    @Test
    void missingOccurredAtDefaultsToNow() {
        IngestionOutcome outcome = service.ingest(TENANT_ID, resources, event("key-2", null));

        assertThat(outcome).isInstanceOf(IngestionOutcome.Accepted.class);
        ArgumentCaptor<UsageEventMessage> captor = ArgumentCaptor.forClass(UsageEventMessage.class);
        verify(publisher).publish(captor.capture());
        assertThat(captor.getValue().occurredAt()).isEqualTo(NOW);
    }

    @Test
    void exactlyAtTheMaxAgeBoundaryIsAccepted() {
        Instant boundary = NOW.minus(properties.maxEventAge());

        IngestionOutcome outcome = service.ingest(TENANT_ID, resources, event("key-3", boundary));

        assertThat(outcome).isInstanceOf(IngestionOutcome.Accepted.class);
    }

    @Test
    void oneMillisecondOlderThanMaxAgeIsRejected() {
        Instant tooOld = NOW.minus(properties.maxEventAge()).minusMillis(1);

        IngestionOutcome outcome = service.ingest(TENANT_ID, resources, event("key-4", tooOld));

        assertThat(outcome).isInstanceOf(IngestionOutcome.Rejected.class);
        assertThat(((IngestionOutcome.Rejected) outcome).code()).isEqualTo("EVENT_TOO_OLD");
        verify(publisher, never()).publish(any());
    }

    @Test
    void exactlyAtTheMaxSkewBoundaryIsAccepted() {
        Instant boundary = NOW.plus(properties.maxClockSkew());

        IngestionOutcome outcome = service.ingest(TENANT_ID, resources, event("key-5", boundary));

        assertThat(outcome).isInstanceOf(IngestionOutcome.Accepted.class);
    }

    @Test
    void oneMillisecondBeyondMaxSkewIsRejected() {
        Instant tooFuture = NOW.plus(properties.maxClockSkew()).plusMillis(1);

        IngestionOutcome outcome = service.ingest(TENANT_ID, resources, event("key-6", tooFuture));

        assertThat(outcome).isInstanceOf(IngestionOutcome.Rejected.class);
        assertThat(((IngestionOutcome.Rejected) outcome).code()).isEqualTo("EVENT_IN_FUTURE");
        verify(publisher, never()).publish(any());
    }

    @Test
    void duplicateWithMatchingFingerprintIsNotRepublished() {
        when(idempotency.lookup(TENANT_ID, "key-7")).thenReturn(Optional.of(DEFAULT_FINGERPRINT));

        IngestionOutcome outcome = service.ingest(TENANT_ID, resources, event("key-7", NOW));

        assertThat(outcome).isEqualTo(new IngestionOutcome.Duplicate("key-7"));
        verify(publisher, never()).publish(any());
    }

    @Test
    void sameKeyWithDifferentPayloadIsRejectedAsReuse() {
        when(idempotency.lookup(TENANT_ID, "key-8")).thenReturn(Optional.of("a-different-fingerprint"));

        IngestionOutcome outcome = service.ingest(TENANT_ID, resources, event("key-8", NOW));

        assertThat(outcome).isInstanceOf(IngestionOutcome.Rejected.class);
        IngestionOutcome.Rejected rejected = (IngestionOutcome.Rejected) outcome;
        assertThat(rejected.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        verify(publisher, never()).publish(any());
    }

    @Test
    void unknownResourceIsRejectedOnlyAfterARefresh() {
        AlwaysContainsResourceIdSource neverFound = new AlwaysContainsResourceIdSource(false);

        IngestionOutcome outcome = service.ingest(TENANT_ID, neverFound, event("key-9", NOW));

        assertThat(outcome).isInstanceOf(IngestionOutcome.Rejected.class);
        assertThat(((IngestionOutcome.Rejected) outcome).code()).isEqualTo("UNKNOWN_RESOURCE");
        assertThat(neverFound.refreshCalls).isEqualTo(1);
        verify(publisher, never()).publish(any());
    }

    @Test
    void resourceFoundOnlyAfterRefreshIsAccepted() {
        ResourceIdSource foundOnRefresh = new ResourceIdSource() {
            @Override
            public boolean contains(UUID resourceId) {
                return false;
            }

            @Override
            public boolean refreshContains(UUID resourceId) {
                return true;
            }
        };

        IngestionOutcome outcome = service.ingest(TENANT_ID, foundOnRefresh, event("key-10", NOW));

        assertThat(outcome).isInstanceOf(IngestionOutcome.Accepted.class);
    }

    /** A {@link ResourceIdSource} test double that reports a fixed containment result and counts
     * refresh calls, so the "refresh once" contract can be asserted directly. */
    private static final class AlwaysContainsResourceIdSource implements ResourceIdSource {
        private final boolean found;
        private int refreshCalls = 0;

        AlwaysContainsResourceIdSource(boolean found) {
            this.found = found;
        }

        @Override
        public boolean contains(UUID resourceId) {
            return found;
        }

        @Override
        public boolean refreshContains(UUID resourceId) {
            refreshCalls++;
            return found;
        }
    }
}
