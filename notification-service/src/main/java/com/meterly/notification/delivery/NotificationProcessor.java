package com.meterly.notification.delivery;

import com.meterly.common.event.NotificationEvent;
import com.meterly.common.tenant.TenantContext;
import com.meterly.notification.delivery.DeliveryOutcome.Failed;
import com.meterly.notification.delivery.DeliveryOutcome.Sent;
import com.meterly.notification.template.NotificationTemplates;
import com.meterly.notification.template.RenderedEmail;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Handles one {@link NotificationEvent}, idempotently under at-least-once delivery:
 * <ol>
 *   <li>render subject/body ({@link NotificationTemplates}, pattern-matching dispatch);</li>
 *   <li>in one transaction bound to the event's tenant (RLS): resolve the active TENANT_ADMIN recipients and
 *       claim each (event, recipient) by inserting its PENDING row with
 *       {@code ON CONFLICT (tenant_id, event_id, recipient) DO NOTHING RETURNING id};</li>
 *   <li>after commit, send only the freshly claimed rows and record each outcome.</li>
 * </ol>
 * A redelivered event finds every row already present, claims nothing and sends nothing. Exceptions from
 * step 2 (e.g. database unavailable) propagate so the Kafka error handler retries the record; nothing has
 * been sent at that point.
 */
@Service
public class NotificationProcessor {

    private final NotificationTemplates templates;
    private final RecipientRepository recipients;
    private final NotificationRepository notifications;
    private final NotificationDelivery delivery;
    private final RetryPolicy retryPolicy;
    private final TransactionTemplate tx;

    public NotificationProcessor(NotificationTemplates templates, RecipientRepository recipients,
                                 NotificationRepository notifications, NotificationDelivery delivery,
                                 RetryPolicy retryPolicy, TransactionTemplate tx) {
        this.templates = templates;
        this.recipients = recipients;
        this.notifications = notifications;
        this.delivery = delivery;
        this.retryPolicy = retryPolicy;
        this.tx = tx;
    }

    public ProcessingSummary process(NotificationEvent event) {
        RenderedEmail email = templates.render(event);
        Claims claims = TenantContext.callAs(event.tenantId(), () -> tx.execute(status -> claim(event, email)));

        int sent = 0;
        int failed = 0;
        for (PendingNotification notification : claims.claimed()) {
            switch (delivery.deliver(notification)) {
                case Sent s -> sent++;
                case Failed f -> failed++;
            }
        }
        return new ProcessingSummary(email.type(), claims.recipients(), claims.claimed().size(), sent, failed);
    }

    private Claims claim(NotificationEvent event, RenderedEmail email) {
        UUID tenantId = event.tenantId();
        List<String> admins = recipients.activeAdminEmails(tenantId);
        List<PendingNotification> claimed = new ArrayList<>(admins.size());
        for (String recipient : admins) {
            notifications.claim(tenantId, event.eventId(), email, recipient, retryPolicy.pendingLease())
                    .ifPresent(id -> claimed.add(new PendingNotification(id, tenantId, event.eventId(), email.type(),
                            recipient, email.subject(), email.body(), 1)));
        }
        return new Claims(admins.size(), claimed);
    }

    private record Claims(int recipients, List<PendingNotification> claimed) {
    }

    /**
     * @param recipients active admins found for the tenant
     * @param claimed    rows newly inserted by this delivery ({@code recipients − claimed} were duplicates)
     */
    public record ProcessingSummary(String type, int recipients, int claimed, int sent, int failed) {

        public int duplicates() {
            return recipients - claimed;
        }
    }
}
