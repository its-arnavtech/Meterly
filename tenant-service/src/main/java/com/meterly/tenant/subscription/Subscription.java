package com.meterly.tenant.subscription;

import com.meterly.tenant.plan.Plan;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Contract "Subscription" object: {@code { id, plan, start_date, end_date, status }}; the subscription
 * covers {@code [start_date, end_date)} and {@code end_date = null} means open-ended.
 */
public record Subscription(UUID id, Plan plan, LocalDate startDate, LocalDate endDate, String status) {

    /** Response of {@code GET/PUT /v1/subscription}: the active subscription and any pending change. */
    public record Overview(Subscription current, Subscription scheduled) {
    }
}
