package com.meterly.notification.delivery;

import com.meterly.common.tenant.TenantContext;
import com.meterly.notification.delivery.DeliveryOutcome.Failed;
import com.meterly.notification.delivery.DeliveryOutcome.Sent;
import com.meterly.notification.email.EmailAddresses;
import com.meterly.notification.email.EmailSender;
import com.meterly.notification.email.OutgoingEmail;
import java.time.Duration;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends one claimed notification and records the outcome. Shared by the Kafka consumer (first attempt)
 * and the retry job (later attempts).
 *
 * <p>The send happens outside any database transaction. Provider errors never propagate: they become a
 * FAILED row with the error and a scheduled retry. If recording the outcome itself fails (database
 * outage), the row stays PENDING and the retry job re-sends it once its lease expires; that rare path is
 * the only one that can produce a duplicate email (at-least-once), chosen over silently losing it.
 */
@Component
public class NotificationDelivery {

    private static final Logger log = LoggerFactory.getLogger(NotificationDelivery.class);
    private static final int MAX_ERROR_LENGTH = 1000;
    private static final Pattern CONTROL_CHARS = Pattern.compile("\\p{Cntrl}+");

    private final EmailSender emailSender;
    private final NotificationRepository repository;
    private final RetryPolicy retryPolicy;
    private final NotificationMetrics metrics;
    private final TransactionTemplate tx;

    public NotificationDelivery(EmailSender emailSender, NotificationRepository repository, RetryPolicy retryPolicy,
                                NotificationMetrics metrics, TransactionTemplate tx) {
        this.emailSender = emailSender;
        this.repository = repository;
        this.retryPolicy = retryPolicy;
        this.metrics = metrics;
        this.tx = tx;
    }

    public DeliveryOutcome deliver(PendingNotification notification) {
        DeliveryOutcome outcome = send(notification);
        metrics.sendAttempt(notification.type(), outcome);
        try {
            record(notification, outcome);
        } catch (RuntimeException e) {
            log.warn("Could not record outcome '{}' of notification {} (attempt {}); it stays PENDING and will be "
                            + "retried after its lease expires", outcome.tag(), notification.id(), notification.attempts(), e);
        }
        return outcome;
    }

    private DeliveryOutcome send(PendingNotification notification) {
        try {
            emailSender.send(new OutgoingEmail(notification.recipient(), notification.subject(), notification.body()));
            log.info("Sent {} notification {} to {} (attempt {})", notification.type(), notification.id(),
                    EmailAddresses.redact(notification.recipient()), notification.attempts());
            return new Sent();
        } catch (RuntimeException e) {
            return new Failed(describe(e));
        }
    }

    private void record(PendingNotification notification, DeliveryOutcome outcome) {
        TenantContext.runAs(notification.tenantId(), () -> tx.executeWithoutResult(status -> {
            switch (outcome) {
                case Sent sent -> repository.markSent(notification.id());
                case Failed failed -> {
                    Duration retryIn = retryPolicy.backoffAfter(notification.attempts()).orElse(null);
                    repository.markFailed(notification.id(), failed.error(), retryIn);
                    if (retryIn == null) {
                        log.error("Giving up on {} notification {} to {} after {} attempt(s): {}", notification.type(),
                                notification.id(), EmailAddresses.redact(notification.recipient()),
                                notification.attempts(), failed.error());
                    } else {
                        log.warn("Sending {} notification {} to {} failed (attempt {}), retrying in {}: {}",
                                notification.type(), notification.id(), EmailAddresses.redact(notification.recipient()),
                                notification.attempts(), retryIn, failed.error());
                    }
                }
            }
        }));
    }

    /** Exception class and message, single-line and bounded, for {@code notification.error}. */
    static String describe(Throwable error) {
        String message = error.getMessage();
        String text = error.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
        text = CONTROL_CHARS.matcher(text).replaceAll(" ").strip();
        return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH - 3) + "...";
    }
}
