package com.meterly.billing;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Rows remain pending until Kafka acknowledges them. Crash after send is safe: consumers deduplicate event_id. */
@Component
public class OutboxRelay {
    private record Event(UUID id, String topic, String key, String payload) {}
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final MeterRegistry metrics;
    public OutboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka,
                       PlatformTransactionManager manager, MeterRegistry metrics) {
        this.jdbc = jdbc; this.kafka = kafka; this.tx = new TransactionTemplate(manager); this.metrics = metrics;
    }
    @Scheduled(fixedDelayString = "${meterly.billing.outbox-delay:2000}")
    @SchedulerLock(name = "billing-outbox-relay", lockAtMostFor = "PT2M")
    public void relay() {
        // Bounded work prevents a broker outage from holding a large batch of locks indefinitely.
        for (int i = 0; i < 50; i++) {
            Boolean sent = tx.execute(status -> {
                var events = jdbc.query("""
                        SELECT id, topic, event_key, payload::text FROM outbox_event
                        WHERE published_at IS NULL ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1
                        """, (rs, n) -> new Event(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4)));
                if (events.isEmpty()) return false;
                var event = events.getFirst();
                try {
                    kafka.send(event.topic(), event.key(), event.payload()).get(10, TimeUnit.SECONDS);
                    jdbc.update("UPDATE outbox_event SET published_at = now(), attempts = attempts + 1, last_error = NULL WHERE id = ?", event.id());
                    metrics.counter("meterly_outbox_published_total").increment();
                    return true;
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    // Never persist exception messages: broker strings may contain endpoint credentials.
                    jdbc.update("UPDATE outbox_event SET attempts = attempts + 1, last_error = ? WHERE id = ?", e.getClass().getSimpleName(), event.id());
                    metrics.counter("meterly_outbox_failures_total").increment();
                    return false;
                }
            });
            if (!Boolean.TRUE.equals(sent)) break;
        }
    }
}
