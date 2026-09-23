package com.meterly.billing.web;

import com.meterly.billing.run.BillingPeriods;
import com.meterly.billing.run.PeriodCloseJob;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Service-to-service operations ({@code X-Internal-Token}). */
@RestController
@RequestMapping("/internal/v1/billing")
public class InternalBillingController {

    private final PeriodCloseJob periodClose;
    private final BillingPeriods periods;

    public InternalBillingController(PeriodCloseJob periodClose, BillingPeriods periods) {
        this.periodClose = periodClose;
        this.periods = periods;
    }

    /**
     * Runs the period close now for every active tenant (default: the previous month) and returns
     * {@code { "period_start", "created", "already_invoiced", "skipped_no_subscription", "failed" }}.
     */
    @PostMapping("/run-all")
    public PeriodCloseJob.Summary runAll(@RequestBody(required = false) BillingRunRequest request) {
        return periodClose.runAll(periods.resolve(request == null ? null : request.periodStart()));
    }
}
