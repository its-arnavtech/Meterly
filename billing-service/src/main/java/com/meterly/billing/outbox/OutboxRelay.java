package com.meterly.billing.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meterly.billing.config.BillingProperties;
import com.meterly.billing.outbox.OutboxRepository.OutboxEvent;
import com.meterly.common.event.NotificationEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Relays billing's own outbox rows ({@code event_type = 'invoice.issued'}; metering-service relays its
 * threshold events itself) to Kafka, keyed by tenant id.
 *
 * <p>One run claims a batch with {@code FOR UPDATE SKIP LOCKED}, sends every record (pipelined), then waits
 * for each broker acknowledgement ({@code acks=all}, idempotent producer) and marks the row
 * {@code published_at} — all in one transaction. A failed or timed-out send increments {@code attempts} and
 * records the error type in {@code last_error}; the row is retried on the next run. Delivery is
 * at-least-once (a crash between ack and commit re-sends); consumers deduplicate by {@code event_id}.
 */
@Component
public class OutboxRelay {

    public static final String EVENT_TYPE = NotificationEvent.INVOICE_ISSUED;
    static final String PUBLISHED_METRIC = "meterly.billing.outbox.published";
    static final String FAILED_METRIC = "meterly.billing.outbox.failed";
    static final String BACKLOG_METRIC = "meterly.billing.outbox.backlog";

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final BillingProperties.Outbox settings;
    private final Clock clock;
    private final Counter published;
    private final Counter failed;
    private final AtomicLong backlog = new AtomicLong();

    public OutboxRelay(OutboxRepository outbox, KafkaTemplate<String, String> kafka, ObjectMapper objectMapper,
                       TransactionTemplate transactions, BillingProperties properties, Clock clock,
                       MeterRegistry registry) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.objectMapper = objectMapper;
        this.transactions = transactions;
        this.settings = properties.outbox();
        this.clock = clock;
        this.published = Counter.builder(PUBLISHED_METRIC).description("Outbox events acknowledged by Kafka")
                .register(registry);
        this.failed = Counter.builder(FAILED_METRIC).description("Outbox publish attempts that failed")
                .register(registry);
        Gauge.builder(BACKLOG_METRIC, backlog, AtomicLong::doubleValue)
                .description("Unpublished invoice.issued outbox events after the last relay run on this instance")
                .register(registry);
    }

    /** Outcome of one relay run. */
    public record RelayResult(int published, int failed) {
    }

    @Scheduled(fixedDelayString = "${meterly.billing.outbox.relay-interval:PT2S}")
    @SchedulerLock(name = "billing-outbox-relay", lockAtMostFor = "PT1M")
    public void relayOnSchedule() {
        relay();
    }

    /** Publishes at most one batch of pending events and returns how many were acknowledged or failed. */
    public RelayResult relay() {
        RelayResult result = transactions.execute(status -> relayBatch());
        backlog.set(outbox.countUnpublished(EVENT_TYPE));
        if (result.published() > 0 || result.failed() > 0) {
            log.info("Outbox relay: {} {} event(s) published, {} failed", result.published(), EVENT_TYPE,
                    result.failed());
        }
        return result;
    }

    private RelayResult relayBatch() {
        List<OutboxEvent> batch = outbox.lockUnpublished(EVENT_TYPE, settings.batchSize());
        List<InFlight> inFlight = batch.stream().map(this::send).toList();
        int acknowledged = 0;
        int failures = 0;
        for (InFlight send : inFlight) {
            Optional<String> error = awaitAck(send);
            if (error.isEmpty()) {
                outbox.markPublished(send.event().id(), clock.instant());
                acknowledged++;
            } else {
                log.warn("Outbox event {} (tenant {}) not published: {}", send.event().id(), send.event().tenantId(),
                        error.get());
                outbox.markFailed(send.event().id(), error.get());
                failures++;
            }
        }
        published.increment(acknowledged);
        failed.increment(failures);
        return new RelayResult(acknowledged, failures);
    }

    private record InFlight(OutboxEvent event, CompletableFuture<?> ack) {
    }

    private InFlight send(OutboxEvent event) {
        try {
            return new InFlight(event, kafka.send(event.topic(), event.key(), canonicalJson(event.payload())));
        } catch (JsonProcessingException | RuntimeException e) {
            return new InFlight(event, CompletableFuture.failedFuture(e));
        }
    }

    /**
     * {@code jsonb} storage reorders keys and adds whitespace; re-serializing through the sealed event type
     * restores the canonical wire format (compact, {@code type} discriminator first) and refuses to publish a
     * payload consumers could not read.
     */
    private String canonicalJson(String payload) throws JsonProcessingException {
        NotificationEvent event = objectMapper.readValue(payload, NotificationEvent.class);
        return objectMapper.writerFor(NotificationEvent.class).writeValueAsString(event);
    }

    private Optional<String> awaitAck(InFlight send) {
        try {
            send.ack().get(settings.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.of(describe(e));
        } catch (ExecutionException e) {
            return Optional.of(describe(e.getCause() == null ? e : e.getCause()));
        } catch (TimeoutException e) {
            return Optional.of(describe(e));
        }
    }

    /** Only the exception type: broker and client messages can contain endpoints or credentials. */
    private static String describe(Throwable error) {
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(error);
        return cause == error ? error.getClass().getName()
                : error.getClass().getName() + " caused by " + cause.getClass().getName();
    }
}
