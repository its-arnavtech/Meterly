package com.meterly.billing.invoice;

import com.meterly.billing.BillingErrors;
import com.meterly.common.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side for issued invoices, on the request thread (tenant bound by {@code TenantContextAspect}).
 * Another tenant's invoice id is indistinguishable from an unknown one: RLS hides the row, and the API
 * answers {@code 404 INVOICE_NOT_FOUND} for both.
 */
@Service
public class InvoiceQueryService {

    private final InvoiceRepository invoices;

    public InvoiceQueryService(InvoiceRepository invoices) {
        this.invoices = invoices;
    }

    @Transactional(readOnly = true)
    public List<InvoiceSummary> list() {
        return invoices.list(TenantContext.require());
    }

    @Transactional(readOnly = true)
    public InvoiceDetail get(UUID invoiceId) {
        return invoices.findDetail(TenantContext.require(), invoiceId).orElseThrow(BillingErrors::invoiceNotFound);
    }
}
