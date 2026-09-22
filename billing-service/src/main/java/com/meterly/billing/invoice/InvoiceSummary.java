package com.meterly.billing.invoice;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One row of {@code GET /v1/invoices}. */
public record InvoiceSummary(
        UUID id,
        String invoiceNumber,
        LocalDate periodStart,
        LocalDate periodEnd,
        String status,
        String currency,
        BigDecimal totalAmount,
        Instant issuedAt) {
}
