package com.meterly.billing.invoice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record InvoiceView(
        UUID id,
        UUID tenantId,
        LocalDate periodStart,
        LocalDate periodEnd,
        String status,
        BigDecimal totalAmount,
        List<LineItemView> lineItems
) {
    public record LineItemView(
            UUID id,
            String description,
            long quantity,
            BigDecimal unitPrice,
            BigDecimal amount
    ) {
    }
}

