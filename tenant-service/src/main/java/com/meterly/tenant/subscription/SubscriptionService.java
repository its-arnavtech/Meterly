package com.meterly.tenant.subscription;

import com.meterly.common.security.CurrentUser;
import com.meterly.common.web.ApiException;
import com.meterly.tenant.account.TenantRepository;
import com.meterly.tenant.plan.Plan;
import com.meterly.tenant.plan.PlanRepository;
import com.meterly.tenant.security.CallerVerifier;
import java.time.LocalDate;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads and changes the tenant's subscription. All methods run in the caller's tenant transaction. */
@Service
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    private final SubscriptionRepository subscriptions;
    private final PlanRepository plans;
    private final TenantRepository tenants;
    private final CallerVerifier callers;
    private final BillingCalendar calendar;

    public SubscriptionService(SubscriptionRepository subscriptions, PlanRepository plans, TenantRepository tenants,
                               CallerVerifier callers, BillingCalendar calendar) {
        this.subscriptions = subscriptions;
        this.plans = plans;
        this.tenants = tenants;
        this.callers = callers;
        this.calendar = calendar;
    }

    @Transactional(readOnly = true)
    public Subscription.Overview overview(CurrentUser caller) {
        callers.requireActiveAdmin(caller);
        return load(calendar.today());
    }

    /** Current subscription only, for the signup response (runs inside the signup transaction). */
    public Subscription current() {
        return load(calendar.today()).current();
    }

    @Transactional
    public Subscription.Overview changePlan(CurrentUser caller, String planCode) {
        callers.requireActiveAdmin(caller);
        Plan target = plans.findActiveByCode(planCode)
                .orElseThrow(() -> ApiException.unprocessable("UNKNOWN_PLAN", "Unknown or inactive plan: " + planCode));
        // One plan change at a time per tenant: concurrent PUTs would otherwise race on the pending row.
        tenants.lock(caller.tenantId());
        LocalDate today = calendar.today();
        PlanChange change = PlanChange.decide(
                subscriptions.findCovering(today), subscriptions.findStartingAfter(today), target.id(), today);
        switch (change) {
            case PlanChange.NoChange noChange -> {
            }
            case PlanChange.KeepCurrent keep -> {
                subscriptions.cancelStartingAfter(today);
                subscriptions.setEndDate(keep.currentSubscriptionId(), null);
            }
            case PlanChange.Schedule schedule -> {
                subscriptions.cancelStartingAfter(today);
                subscriptions.setEndDate(schedule.currentSubscriptionId(), schedule.startDate());
                subscriptions.insert(caller.tenantId(), schedule.planId(), schedule.startDate());
            }
            case PlanChange.StartNow startNow -> {
                subscriptions.cancelStartingAfter(today);
                subscriptions.insert(caller.tenantId(), startNow.planId(), startNow.startDate());
            }
        }
        log.info("Plan change for tenant {}: {} -> {}", caller.tenantId(), change.getClass().getSimpleName(),
                target.code());
        return load(today);
    }

    private Subscription.Overview load(LocalDate today) {
        Subscription current = subscriptions.findCovering(today).map(this::toView).orElse(null);
        Subscription scheduled = subscriptions.findStartingAfter(today).map(this::toView).orElse(null);
        return new Subscription.Overview(current, scheduled);
    }

    private Subscription toView(SubscriptionRepository.Row row) {
        Plan plan = plans.findById(row.planId())
                .orElseThrow(() -> new IllegalStateException("Subscription " + row.id() + " refers to a missing plan"));
        return new Subscription(row.id(), plan, row.startDate(), row.endDate(), row.status());
    }

    /** Creates the first subscription of a new tenant, starting today (base fee not prorated). */
    public void start(UUID tenantId, UUID planId) {
        subscriptions.insert(tenantId, planId, calendar.today());
    }
}
