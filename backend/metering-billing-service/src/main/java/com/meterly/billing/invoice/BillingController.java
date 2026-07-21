package com.meterly.billing.invoice;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.meterly.billing.tenant.TenantContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1")
public class BillingController {
    private final BillingService billingService;

    public BillingController(BillingService billingService) {
        this.billingService = billingService;
    }

    @PostMapping("/billing/run")
    public InvoiceView run(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestBody BillingRunRequest request
    ) {
        try (TenantContext.Scope ignored = TenantContext.open(tenantId)) {
            return billingService.run(tenantId, request.periodStart(), request.periodEnd());
        }
    }

    @GetMapping("/invoices")
    public List<InvoiceView> list(@RequestHeader("X-Tenant-Id") UUID tenantId) {
        try (TenantContext.Scope ignored = TenantContext.open(tenantId)) {
            return billingService.list();
        }
    }

    @GetMapping("/invoices/{invoiceId}")
    public InvoiceView get(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID invoiceId
    ) {
        try (TenantContext.Scope ignored = TenantContext.open(tenantId)) {
            return billingService.find(invoiceId);
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record BillingRunRequest(LocalDate periodStart, LocalDate periodEnd) {
    }
}

