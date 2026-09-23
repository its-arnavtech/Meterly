package com.meterly.metering.counter;

import com.meterly.common.event.UsageEventMessage;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The append-only usage ledger ({@code usage_event}), the source of truth for usage and billing. All
 * methods run under RLS inside a tenant-bound transaction.
 */
@Repository
public class UsageLedgerRepository {

    /** A ledger row created by {@link #insert}. */
    public record LedgerRow(long id, Instant recordedAt) {
    }

    /** Where an already-recorded idempotency key was counted. */
    public record RecordedEvent(UUID resourceId, Instant occurredAt) {
    }

    /** Units in one UTC time bucket. */
    public record BucketTotal(Instant bucketStart, long units) {
    }

    private final JdbcClient jdbc;

    public UsageLedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Takes the transaction-scoped advisory lock of a counter. It is held until COMMIT/ROLLBACK, so the
     * ledger id and projection version of concurrent writers to the same counter follow commit order.
     */
    public void lock(CounterKey key) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:name, 0))")
                .param("name", key.lockName())
                .query((rs, row) -> Boolean.TRUE)
                .single();
    }

    /** Inserts the event unless its idempotency key was already recorded for the tenant. */
    public Optional<LedgerRow> insert(UsageEventMessage event) {
        return jdbc.sql("""
                        INSERT INTO usage_event (tenant_id, resource_id, idempotency_key, quantity, occurred_at, received_at)
                        VALUES (:tenantId, :resourceId, :idempotencyKey, :quantity, :occurredAt, :receivedAt)
                        ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
                        RETURNING id, recorded_at
                        """)
                .param("tenantId", event.tenantId())
                .param("resourceId", event.resourceId())
                .param("idempotencyKey", event.idempotencyKey())
                .param("quantity", event.quantity())
                .param("occurredAt", utc(event.occurredAt()))
                .param("receivedAt", utc(event.receivedAt()))
                .query((rs, row) -> new LedgerRow(
                        rs.getLong("id"), rs.getObject("recorded_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    public Optional<RecordedEvent> findByIdempotencyKey(UUID tenantId, String idempotencyKey) {
        return jdbc.sql("""
                        SELECT resource_id, occurred_at
                          FROM usage_event
                         WHERE tenant_id = :tenantId AND idempotency_key = :idempotencyKey
                        """)
                .param("tenantId", tenantId)
                .param("idempotencyKey", idempotencyKey)
                .query((rs, row) -> new RecordedEvent(
                        rs.getObject("resource_id", UUID.class),
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    /** Recomputes a counter from the ledger: total units and the highest ledger id in the period. */
    public CounterSnapshot sum(CounterKey key) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(quantity), 0) AS total_units, COALESCE(MAX(id), 0) AS ledger_version
                          FROM usage_event
                         WHERE tenant_id = :tenantId AND resource_id = :resourceId
                           AND occurred_at >= :from AND occurred_at < :to
                        """)
                .param("tenantId", key.tenantId())
                .param("resourceId", key.resourceId())
                .param("from", utc(key.period().startInstant()))
                .param("to", utc(key.period().endInstant()))
                .query((rs, row) -> new CounterSnapshot(rs.getLong("total_units"), rs.getLong("ledger_version")))
                .single();
    }

    /**
     * Units per UTC bucket in {@code [from, to)}; only buckets with usage are returned.
     *
     * @param unit {@code hour} or {@code day} (a {@code date_trunc} field)
     */
    public List<BucketTotal> buckets(UUID tenantId, UUID resourceId, String unit, Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT date_trunc(:unit, occurred_at, 'UTC') AS bucket_start, SUM(quantity) AS units
                          FROM usage_event
                         WHERE tenant_id = :tenantId AND resource_id = :resourceId
                           AND occurred_at >= :from AND occurred_at < :to
                         GROUP BY bucket_start
                         ORDER BY bucket_start
                        """)
                .param("unit", unit)
                .param("tenantId", tenantId)
                .param("resourceId", resourceId)
                .param("from", utc(from))
                .param("to", utc(to))
                .query((rs, row) -> new BucketTotal(
                        rs.getObject("bucket_start", OffsetDateTime.class).toInstant(), rs.getLong("units")))
                .list();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
