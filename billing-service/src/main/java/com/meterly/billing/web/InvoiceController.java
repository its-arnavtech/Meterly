package com.meterly.billing.web;

import com.meterly.billing.invoice.InvoiceDetail;
import com.meterly.billing.invoice.InvoiceQueryService;
import com.meterly.billing.invoice.InvoiceSummary;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Issued invoices of the caller's tenant. */
@RestController
@RequestMapping("/v1/invoices")
public class InvoiceController {

    private final InvoiceQueryService invoices;

    public InvoiceController(InvoiceQueryService invoices) {
        this.invoices = invoices;
    }

    /** List wrapper per the contract: {@code { "items": [ ... ] }}. */
    public record Items<T>(List<T> items) {
    }

    /** Newest period first. */
    @GetMapping
    public Items<InvoiceSummary> list() {
        return new Items<>(invoices.list());
    }

    /** {@code 404 INVOICE_NOT_FOUND} for unknown ids and for other tenants' invoices alike. */
    @GetMapping("/{invoiceId}")
    public InvoiceDetail get(@PathVariable UUID invoiceId) {
        return invoices.get(invoiceId);
    }
}
