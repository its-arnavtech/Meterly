package com.meterly.tenant.plan;

import com.meterly.tenant.support.Items;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public plan catalog; the SPA's signup page and the validation harness price invoices from it. */
@RestController
public class PlanController {

    private final PlanRepository plans;

    public PlanController(PlanRepository plans) {
        this.plans = plans;
    }

    @GetMapping("/v1/plans")
    public Items<Plan> plans() {
        return Items.of(plans.findActive());
    }
}
