package com.meterly.notification.delivery;

import com.meterly.common.tenant.TenantContext;
import com.meterly.notification.config.NotificationProperties;
import com.meterly.notification.delivery.DeliveryOutcome.Failed;
import com.meterly.notification.delivery.DeliveryOutcome.Sent;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Re-delivers notifications that did not reach SENT on their first attempt.
 *
 * <p>A row is due when its {@code next_attempt_at} has passed. That covers two cases:
 * <ul>
 *   <li><b>FAILED</b>: the provider rejected the send; {@code next_attempt_at} was set with exponential
 *       backoff ({@link RetryPolicy#backoffAfter}).</li>
 *   <li><b>PENDING with an expired lease</b>: the process crashed (or lost the database) between claiming
 *       the row and recording the result. Whether the email went out is unknown; it is sent again
 *       (at-least-once), because a missing invoice notice is worse than a rare duplicate.</li>
 * </ul>
 * Every {@code meterly.notification.retry.interval} the job asks the SECURITY DEFINER function
 * {@code notification_retry_due_tenant_ids()} which tenants have due rows, then per tenant, under RLS:
 * marks rows that already used {@code max-attempts} as terminally FAILED, re-claims up to
 * {@code batch-size} due rows ({@code attempts + 1}, fresh lease, {@code FOR UPDATE SKIP LOCKED}) and sends
 * them. The lease plus SKIP LOCKED make it safe to run on every replica without a distributed lock.
 * Failures are isolated per tenant.
 */
@Component
public class NotificationRetryJob {

    private static final Logger log = LoggerFactory.getLogger(NotificationRetryJob.class);
    private static final String MDC_TENANT_ID = "tenant_id";

    private final NotificationRepository repository;
    private final NotificationDelivery delivery;
    private final RetryPolicy retryPolicy;
    private final TransactionTemplate tx;
    private final boolean enabled;

    public NotificationRetryJob(NotificationRepository repository, NotificationDelivery delivery,
                                RetryPolicy retryPolicy, TransactionTemplate tx, NotificationProperties properties) {
        this.repository = repository;
        this.delivery = delivery;
        this.retryPolicy = retryPolicy;
        this.tx = tx;
        this.enabled = properties.retry().enabled();
    }

    @Scheduled(initialDelayString = "${meterly.notification.retry.interval:60s}",
            fixedDelayString = "${meterly.notification.retry.interval:60s}")
    void scheduledRun() {
        if (!enabled) {
            return;
        }
        try {
            RetrySummary summary = runOnce();
            if (summary.retried() > 0 || summary.abandoned() > 0 || summary.failedTenants() > 0) {
                log.info("Notification retry run: {}", summary);
            }
        } catch (RuntimeException e) {
            log.error("Notification retry run failed", e);
        }
    }

    /** One pass over all tenants with due rows. Public for tests and manual triggering. */
    public RetrySummary runOnce() {
        List<UUID> tenants = tx.execute(status -> repository.tenantsWithDueRetries());
        RetrySummary total = RetrySummary.EMPTY;
        for (UUID tenantId : tenants == null ? List.<UUID>of() : tenants) {
            MDC.put(MDC_TENANT_ID, tenantId.toString());
            try {
                total = total.plus(retryTenant(tenantId));
            } catch (RuntimeException e) {
                log.error("Notification retry failed for tenant {}", tenantId, e);
                total = total.plus(new RetrySummary(1, 0, 0, 0, 0, 1));
            } finally {
                MDC.remove(MDC_TENANT_ID);
            }
        }
        return total;
    }

    private RetrySummary retryTenant(UUID tenantId) {
        int maxAttempts = retryPolicy.maxAttempts();
        int abandoned = TenantContext.callAs(tenantId, () -> tx.execute(status ->
                repository.abandonExhausted(tenantId, maxAttempts)));
        if (abandoned > 0) {
            log.error("Gave up on {} notification(s) of tenant {} whose delivery outcome is unknown after {} attempt(s)",
                    abandoned, tenantId, maxAttempts);
        }
        List<PendingNotification> due = TenantContext.callAs(tenantId, () -> tx.execute(status ->
                repository.claimDueRetries(tenantId, maxAttempts, retryPolicy.batchSize(), retryPolicy.pendingLease())));
        int sent = 0;
        int failed = 0;
        for (PendingNotification notification : due) {
            switch (delivery.deliver(notification)) {
                case Sent s -> sent++;
                case Failed f -> failed++;
            }
        }
        return new RetrySummary(1, due.size(), sent, failed, abandoned, 0);
    }

    /**
     * @param retried   rows re-claimed and attempted again
     * @param abandoned rows given up on because they exhausted their attempts with no recorded result
     */
    public record RetrySummary(int tenants, int retried, int sent, int failed, int abandoned, int failedTenants) {

        static final RetrySummary EMPTY = new RetrySummary(0, 0, 0, 0, 0, 0);

        RetrySummary plus(RetrySummary other) {
            return new RetrySummary(tenants + other.tenants, retried + other.retried, sent + other.sent,
                    failed + other.failed, abandoned + other.abandoned, failedTenants + other.failedTenants);
        }
    }
}
