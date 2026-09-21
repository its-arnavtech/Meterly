package com.meterly.notification.delivery;

import com.meterly.notification.template.RenderedEmail;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL for the {@code notification} delivery state machine (see {@code V40__notification_delivery_status.sql}):
 * <pre>
 *   claim ──► PENDING ──send ok──► SENT
 *                │  └──send error──► FAILED ──(next_attempt_at due, attempts &lt; max)──► PENDING (retry)
 *                └──lease expired (sender died)──► PENDING (retry) / FAILED (attempts exhausted)
 * </pre>
 * All timestamps come from the database clock. Every method except {@link #tenantsWithDueRetries()}
 * must run in a tenant-bound transaction (RLS).
 */
@Repository
public class NotificationRepository {

    private final JdbcClient jdbc;

    public NotificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims the (event, recipient) send by inserting the row first. Returns the new row's id, or empty if
     * the row already existed (redelivered event): the caller must then not send.
     */
    public Optional<UUID> claim(UUID tenantId, UUID eventId, RenderedEmail email, String recipient, Duration lease) {
        return jdbc.sql("""
                        INSERT INTO notification (tenant_id, event_id, type, recipient, subject, body,
                                                  status, attempts, next_attempt_at)
                        VALUES (:tenantId, :eventId, :type, :recipient, :subject, :body,
                                'PENDING', 1, now() + :leaseSeconds * interval '1 second')
                        ON CONFLICT (tenant_id, event_id, recipient) DO NOTHING
                        RETURNING id
                        """)
                .param("tenantId", tenantId)
                .param("eventId", eventId)
                .param("type", email.type())
                .param("recipient", recipient)
                .param("subject", email.subject())
                .param("body", email.body())
                .param("leaseSeconds", lease.toSeconds())
                .query(UUID.class)
                .optional();
    }

    public void markSent(UUID id) {
        jdbc.sql("""
                        UPDATE notification
                           SET status = 'SENT', sent_at = now(), error = NULL, next_attempt_at = NULL, updated_at = now()
                         WHERE id = :id
                        """)
                .param("id", id)
                .update();
    }

    /** @param retryIn delay before the next attempt, or {@code null} when no attempt is left (terminal) */
    public void markFailed(UUID id, String error, Duration retryIn) {
        jdbc.sql("""
                        UPDATE notification
                           SET status = 'FAILED', error = :error,
                               next_attempt_at = now() + CAST(:retrySeconds AS bigint) * interval '1 second',
                               updated_at = now()
                         WHERE id = :id
                        """)
                .param("id", id)
                .param("error", error)
                .param("retrySeconds", retryIn == null ? null : retryIn.toSeconds())
                .update();
    }

    /**
     * Re-claims up to {@code batchSize} due rows of the bound tenant for another attempt: FAILED rows whose
     * backoff elapsed and PENDING rows whose lease expired. {@code SKIP LOCKED} plus the new lease keep
     * concurrent replicas from picking the same row.
     */
    public List<PendingNotification> claimDueRetries(UUID tenantId, int maxAttempts, int batchSize, Duration lease) {
        return jdbc.sql("""
                        UPDATE notification n
                           SET status = 'PENDING',
                               attempts = n.attempts + 1,
                               next_attempt_at = now() + :leaseSeconds * interval '1 second',
                               updated_at = now()
                         WHERE n.id IN (SELECT id
                                          FROM notification
                                         WHERE tenant_id = :tenantId
                                           AND next_attempt_at <= now()
                                           AND attempts < :maxAttempts
                                         ORDER BY next_attempt_at
                                         LIMIT :batchSize
                                           FOR UPDATE SKIP LOCKED)
                        RETURNING n.id, n.tenant_id, n.event_id, n.type, n.recipient, n.subject, n.body, n.attempts
                        """)
                .param("tenantId", tenantId)
                .param("maxAttempts", maxAttempts)
                .param("batchSize", batchSize)
                .param("leaseSeconds", lease.toSeconds())
                .query(PendingNotification.class)
                .list();
    }

    /**
     * Gives up on due rows that have used all attempts: a PENDING row here means the last sender died
     * before recording a result, so the outcome is unknown; it becomes FAILED and is never retried.
     */
    public int abandonExhausted(UUID tenantId, int maxAttempts) {
        return jdbc.sql("""
                        UPDATE notification
                           SET status = 'FAILED',
                               error = CASE WHEN status = 'PENDING'
                                            THEN 'Delivery outcome unknown: no result recorded for attempt ' || attempts
                                                 || '; giving up after ' || attempts || ' attempt(s)'
                                            ELSE error END,
                               next_attempt_at = NULL,
                               updated_at = now()
                         WHERE tenant_id = :tenantId
                           AND next_attempt_at <= now()
                           AND attempts >= :maxAttempts
                        """)
                .param("tenantId", tenantId)
                .param("maxAttempts", maxAttempts)
                .update();
    }

    /** Tenants with at least one due row, via the SECURITY DEFINER function (no tenant bound). */
    public List<UUID> tenantsWithDueRetries() {
        return jdbc.sql("SELECT * FROM notification_retry_due_tenant_ids()")
                .query(UUID.class)
                .list();
    }
}
