package com.meterly.billing.outbox;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL for the transactional outbox ({@code outbox_event}, a system table without RLS). Rows are written in
 * the business transaction and relayed to Kafka afterwards by {@link OutboxRelay}.
 */
@Repository
public class OutboxRepository {

    /** Longest {@code last_error} kept per row. */
    static final int MAX_ERROR_LENGTH = 500;

    private final JdbcClient jdbc;

    public OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** An event to be published: {@code payload} is the JSON value, {@code key} the Kafka record key. */
    public record OutboxEvent(UUID id, UUID tenantId, String topic, String key, String eventType, String payload) {
    }

    /** Appends an event; call inside the transaction of the change it announces. */
    public void insert(OutboxEvent event) {
        jdbc.sql("""
                        INSERT INTO outbox_event (id, tenant_id, topic, event_key, event_type, payload)
                        VALUES (:id, :tenantId, :topic, :key, :eventType, CAST(:payload AS jsonb))
                        """)
                .param("id", event.id())
                .param("tenantId", event.tenantId())
                .param("topic", event.topic())
                .param("key", event.key())
                .param("eventType", event.eventType())
                .param("payload", event.payload())
                .update();
    }

    /**
     * Claims up to {@code limit} unpublished events of one type, oldest first. {@code FOR UPDATE SKIP LOCKED}
     * lets a second relay (or another service's relay) work concurrently without blocking or double-sending;
     * the row locks are held until the caller's transaction ends.
     */
    public List<OutboxEvent> lockUnpublished(String eventType, int limit) {
        return jdbc.sql("""
                        SELECT id, tenant_id, topic, event_key, event_type, payload::text AS payload
                        FROM outbox_event
                        WHERE published_at IS NULL AND event_type = :eventType
                        ORDER BY created_at, id
                        LIMIT :limit
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("eventType", eventType)
                .param("limit", limit)
                .query((rs, row) -> new OutboxEvent(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                        rs.getString("topic"), rs.getString("event_key"), rs.getString("event_type"),
                        rs.getString("payload")))
                .list();
    }

    public void markPublished(UUID id, Instant publishedAt) {
        jdbc.sql("UPDATE outbox_event SET published_at = :publishedAt, last_error = NULL WHERE id = :id")
                .param("publishedAt", OffsetDateTime.ofInstant(publishedAt, ZoneOffset.UTC))
                .param("id", id)
                .update();
    }

    public void markFailed(UUID id, String error) {
        String truncated = error.length() > MAX_ERROR_LENGTH ? error.substring(0, MAX_ERROR_LENGTH) : error;
        jdbc.sql("UPDATE outbox_event SET attempts = attempts + 1, last_error = :error WHERE id = :id")
                .param("error", truncated)
                .param("id", id)
                .update();
    }

    public long countUnpublished(String eventType) {
        return jdbc.sql("SELECT count(*) FROM outbox_event WHERE published_at IS NULL AND event_type = :eventType")
                .param("eventType", eventType)
                .query(Long.class)
                .single();
    }
}
