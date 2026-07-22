package com.meterly.billing.invoice;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.meterly.billing.auth.AuthPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
            Authentication authentication,
            @RequestBody BillingRunRequest request
    ) {
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        return billingService.run(principal.tenantId(), request.periodStart(), request.periodEnd());
    }

    @GetMapping("/invoices")
    public List<InvoiceView> list() {
        return billingService.list();
    }

    @GetMapping("/invoices/{invoiceId}")
    public InvoiceView get(
            @PathVariable UUID invoiceId
    ) {
        return billingService.find(invoiceId);
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record BillingRunRequest(LocalDate periodStart, LocalDate periodEnd) {
    }
}
