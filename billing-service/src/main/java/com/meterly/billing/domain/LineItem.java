package com.meterly.billing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

/**
 * One invoice line: {@code amount = unitPrice x quantity}, rounded once (HALF_EVEN, 2 dp).
 *
 * @param lineNumber  1-based position on the invoice
 * @param description human-readable text, e.g. {@code "API calls — 10,001–100,000"}
 * @param resourceId  the metered resource, or {@code null} for the plan base fee
 * @param quantity    units priced by this line
 * @param unitPrice   scale 6
 * @param amount      scale 2
 */
public record LineItem(
        int lineNumber,
        String description,
        UUID resourceId,
        long quantity,
        BigDecimal unitPrice,
        BigDecimal amount) {

    public LineItem {
        if (lineNumber < 1) {
            throw new IllegalArgumentException("lineNumber must be positive: " + lineNumber);
        }
        Objects.requireNonNull(description, "description");
        if (quantity < 0) {
            throw new IllegalArgumentException("quantity must not be negative: " + quantity);
        }
        Objects.requireNonNull(unitPrice, "unitPrice");
        Objects.requireNonNull(amount, "amount");
        if (unitPrice.scale() != Money.UNIT_PRICE_SCALE || amount.scale() != Money.AMOUNT_SCALE) {
            throw new IllegalArgumentException("unitPrice must have scale 6 and amount scale 2");
        }
    }

    /** Prices {@code quantity x unitPrice} with the single HALF_EVEN rounding step. */
    public static LineItem priced(int lineNumber, String description, UUID resourceId, long quantity,
                                  BigDecimal unitPrice) {
        BigDecimal normalizedPrice = unitPrice.setScale(Money.UNIT_PRICE_SCALE, RoundingMode.UNNECESSARY);
        return new LineItem(lineNumber, description, resourceId, quantity, normalizedPrice,
                Money.lineAmount(normalizedPrice, quantity));
    }
}
