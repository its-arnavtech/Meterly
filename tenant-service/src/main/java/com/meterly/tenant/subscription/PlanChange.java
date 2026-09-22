package com.meterly.tenant.subscription;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * What {@code PUT /v1/subscription} must do, decided purely from the current state so the rules are unit
 * testable: plan changes start at the next billing period (no mid-period proration), and re-selecting the
 * current plan cancels a pending change.
 */
public sealed interface PlanChange {

    /** The requested plan is already in effect (or already scheduled): nothing to write. */
    record NoChange() implements PlanChange {
    }

    /** Re-selected the current plan: drop the pending change and make the current subscription open-ended. */
    record KeepCurrent(UUID currentSubscriptionId) implements PlanChange {
    }

    /** End the current subscription at {@code startDate} and start {@code planId} then. */
    record Schedule(UUID currentSubscriptionId, UUID planId, LocalDate startDate) implements PlanChange {
    }

    /** No subscription covers today (for example after a lapse): start {@code planId} immediately. */
    record StartNow(UUID planId, LocalDate startDate) implements PlanChange {
    }

    static PlanChange decide(Optional<SubscriptionRepository.Row> current,
                             Optional<SubscriptionRepository.Row> scheduled,
                             UUID targetPlanId,
                             LocalDate today) {
        if (current.isEmpty()) {
            return new StartNow(targetPlanId, today);
        }
        SubscriptionRepository.Row active = current.get();
        if (active.planId().equals(targetPlanId)) {
            boolean pendingChange = scheduled.isPresent() || active.endDate() != null;
            return pendingChange ? new KeepCurrent(active.id()) : new NoChange();
        }
        LocalDate boundary = BillingCalendar.nextPeriodStart(today);
        boolean alreadyScheduled = scheduled
                .filter(next -> next.planId().equals(targetPlanId))
                .filter(next -> next.startDate().equals(boundary))
                .filter(next -> boundary.equals(active.endDate()))
                .isPresent();
        return alreadyScheduled ? new NoChange() : new Schedule(active.id(), targetPlanId, boundary);
    }
}
