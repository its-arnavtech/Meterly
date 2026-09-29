package com.meterly.billing.invoice;

import com.meterly.billing.domain.InvoiceDraft;
import com.meterly.billing.domain.LineItem;
import com.meterly.common.billing.BillingPeriod;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Invoice detail as returned by {@code GET /v1/invoices/{id}}, {@code POST /v1/billing/run} and (with
 * {@code id}, {@code invoice_number} and {@code issued_at} null) {@code GET /v1/billing/preview}.
 * Serialized in snake_case with money as strings: unit prices scale 6, amounts scale 2.
 */
public record InvoiceDetail(
        UUID id,
        String invoiceNumber,
        LocalDate periodStart,
        LocalDate periodEnd,
        String status,
        String currency,
        BigDecimal totalAmount,
        Instant issuedAt,
        PlanRef plan,
        List<LineItem> lineItems) {

    public static final String STATUS_ISSUED = "ISSUED";
    public static final String STATUS_PREVIEW = "PREVIEW";

    public InvoiceDetail {
        lineItems = List.copyOf(lineItems);
    }

    /** The plan snapshot stored on the invoice (V30), immune to later catalog renames. */
    public record PlanRef(String code, String name) {
    }

    /** An estimate for an open period: priced like an invoice, never persisted. */
    public static InvoiceDetail preview(BillingPeriod period, InvoiceDraft draft) {
        return new InvoiceDetail(null, null, period.start(), period.end(), STATUS_PREVIEW, draft.currency(),
                draft.totalAmount(), null, new PlanRef(draft.planCode(), draft.planName()), draft.lineItems());
    }
}
