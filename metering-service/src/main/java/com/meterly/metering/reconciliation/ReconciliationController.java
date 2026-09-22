package com.meterly.metering.reconciliation;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * On-demand reconciliation for operators and the validation harness. Guarded by the shared internal
 * token ({@code X-Internal-Token}, role {@code INTERNAL}) in the security chain.
 */
@RestController
@RequestMapping("/internal/v1/reconciliation")
public class ReconciliationController {

    private final ReconciliationService reconciliation;

    public ReconciliationController(ReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @PostMapping("/run")
    public ReconciliationSummary run() {
        return reconciliation.reconcileOpenPeriod();
    }
}
