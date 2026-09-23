package com.meterly.billing.web;

import com.meterly.billing.invoice.InvoiceDetail;
import com.meterly.billing.run.BillingPeriods;
import com.meterly.billing.run.BillingRunResult;
import com.meterly.billing.run.BillingService;
import com.meterly.common.billing.BillingPeriod;
import com.meterly.common.security.CurrentUser;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tenant-admin billing actions: run the period close for the caller's tenant, preview the open period. */
@RestController
@RequestMapping("/v1/billing")
public class BillingController {

    private final BillingService billing;
    private final BillingPeriods periods;

    public BillingController(BillingService billing, BillingPeriods periods) {
        this.billing = billing;
        this.periods = periods;
    }

    /**
     * {@code 201 Created} + {@code Location} when the invoice is generated now, {@code 200 OK} with the
     * existing invoice when the period was already invoiced. Body: invoice detail.
     */
    @PostMapping("/run")
    public ResponseEntity<InvoiceDetail> run(@RequestBody(required = false) BillingRunRequest request,
                                             CurrentUser user) {
        BillingPeriod period = periods.resolve(request == null ? null : request.periodStart());
        return switch (billing.run(user.tenantId(), period)) {
            case BillingRunResult.Created created -> ResponseEntity
                    .created(URI.create("/v1/invoices/" + created.invoice().id()))
                    .body(created.invoice());
            case BillingRunResult.AlreadyInvoiced alreadyInvoiced -> ResponseEntity.ok(alreadyInvoiced.invoice());
        };
    }

    /** Estimated invoice for the open period ({@code status: "PREVIEW"}, not persisted). */
    @GetMapping("/preview")
    public InvoiceDetail preview(CurrentUser user) {
        return billing.preview(user.tenantId());
    }
}
