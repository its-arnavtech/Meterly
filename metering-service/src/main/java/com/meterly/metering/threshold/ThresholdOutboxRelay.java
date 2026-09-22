package com.meterly.metering.threshold;

import com.meterly.common.event.NotificationEvent;
import com.meterly.metering.MeteringMetrics;
import com.meterly.metering.config.MeteringProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transactional-outbox relay for the event type metering-service owns ({@code usage.threshold_crossed});
 * billing-service relays its own types, so the two relays never compete for the same rows.
 *
 * <p>Each run claims a batch with {@code FOR UPDATE SKIP LOCKED}, publishes it (producer {@code acks=all},
 * idempotent), and marks each acknowledged row {@code published_at}; failed rows record the attempt and
 * stay pending for the next run. A crash after the broker ack but before COMMIT republishes the row,
 * which is safe because notification-service deduplicates by {@code event_id} (at-least-once).
 */
@Component
public class ThresholdOutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(ThresholdOutboxRelay.class);
    private static final int MAX_BATCHES_PER_RUN = 10;

    private record PendingEvent(UUID id, String topic, String key, String payload) {
    }

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final MeteringMetrics metrics;
    private final MeteringProperties.Outbox settings;

    public ThresholdOutboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx,
                                MeteringMetrics metrics, MeteringProperties properties) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.tx = tx;
        this.metrics = metrics;
        this.settings = properties.outbox();
    }

    @Scheduled(fixedDelayString = "${meterly.metering.outbox.relay-interval:2s}",
            initialDelayString = "${meterly.metering.outbox.relay-interval:2s}")
    @SchedulerLock(name = "metering-outbox-relay", lockAtMostFor = "PT2M")
    public void relayOnSchedule() {
        relayPending();
    }

    /** Publishes pending threshold events; returns how many were acknowledged by the broker. */
    public int relayPending() {
        int published = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            BatchResult result = tx.execute(status -> relayBatch());
            published += result.published();
            if (result.failed() > 0 || result.claimed() < settings.batchSize()) {
                break;
            }
        }
        return published;
    }

    private record BatchResult(int claimed, int published, int failed) {
    }

    private BatchResult relayBatch() {
        List<PendingEvent> pending = jdbc.sql("""
                        SELECT id, topic, event_key, payload::text AS payload
                          FROM outbox_event
                         WHERE published_at IS NULL AND event_type = :eventType
                         ORDER BY created_at
                         LIMIT :limit
                           FOR UPDATE SKIP LOCKED
                        """)
                .param("eventType", NotificationEvent.USAGE_THRESHOLD_CROSSED)
                .param("limit", settings.batchSize())
                .query((rs, row) -> new PendingEvent(rs.getObject("id", UUID.class), rs.getString("topic"),
                        rs.getString("event_key"), rs.getString("payload")))
                .list();
        if (pending.isEmpty()) {
            return new BatchResult(0, 0, 0);
        }
        List<CompletableFuture<SendResult<String, String>>> sends = new ArrayList<>(pending.size());
        for (PendingEvent event : pending) {
            sends.add(send(event));
        }
        long deadline = System.nanoTime() + settings.sendTimeout().toNanos();
        int published = 0;
        int failed = 0;
        for (int i = 0; i < pending.size(); i++) {
            PendingEvent event = pending.get(i);
            try {
                sends.get(i).get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                markPublished(event.id());
                published++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                markFailed(event.id(), e);
                failed++;
            } catch (ExecutionException | TimeoutException | RuntimeException e) {
                markFailed(event.id(), e);
                failed++;
            }
        }
        metrics.outboxPublished(published);
        if (failed > 0) {
            log.warn("Outbox relay: {} of {} threshold events not acknowledged; they stay pending", failed, pending.size());
        }
        return new BatchResult(pending.size(), published, failed);
    }

    private CompletableFuture<SendResult<String, String>> send(PendingEvent event) {
        try {
            return kafka.send(event.topic(), event.key(), event.payload());
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private void markPublished(UUID id) {
        jdbc.sql("UPDATE outbox_event SET published_at = now(), attempts = attempts + 1, last_error = NULL WHERE id = :id")
                .param("id", id)
                .update();
    }

    private void markFailed(UUID id, Exception e) {
        metrics.outboxFailed();
        // Only the exception type is stored: broker messages can contain endpoints or credentials.
        Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
        jdbc.sql("UPDATE outbox_event SET attempts = attempts + 1, last_error = :error WHERE id = :id")
                .param("error", cause.getClass().getSimpleName())
                .param("id", id)
                .update();
    }
}
