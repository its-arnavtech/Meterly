package com.meterly.platform.notification;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record InvoiceGeneratedMessage(
        UUID eventId,
        UUID tenantId,
        UUID invoiceId,
        BigDecimal totalAmount,
        Instant occurredAt
) { }
