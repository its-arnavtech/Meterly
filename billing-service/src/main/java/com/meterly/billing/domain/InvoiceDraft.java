package com.meterly.billing.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * A fully priced, not yet persisted invoice. Invariant (checked on construction): the total equals the
 * sum of the already-rounded line amounts, so the invoice always adds up to the cent.
 */
public record InvoiceDraft(
        String planCode,
        String planName,
        String currency,
        List<LineItem> lineItems,
        BigDecimal totalAmount) {

    public InvoiceDraft {
        Objects.requireNonNull(planCode, "planCode");
        Objects.requireNonNull(planName, "planName");
        Objects.requireNonNull(currency, "currency");
        lineItems = List.copyOf(lineItems);
        Objects.requireNonNull(totalAmount, "totalAmount");
        BigDecimal sum = sumOf(lineItems);
        if (totalAmount.scale() != Money.AMOUNT_SCALE || totalAmount.compareTo(sum) != 0) {
            throw new IllegalArgumentException("Invoice total " + totalAmount.toPlainString()
                    + " does not equal the sum of its line amounts " + sum.toPlainString());
        }
    }

    /** Builds the draft for {@code plan}, deriving the total from the line amounts. */
    public static InvoiceDraft of(PlanPricing plan, List<LineItem> lineItems) {
        return new InvoiceDraft(plan.code(), plan.name(), plan.currency(), lineItems, sumOf(lineItems));
    }

    private static BigDecimal sumOf(List<LineItem> lineItems) {
        return lineItems.stream().map(LineItem::amount).reduce(Money.ZERO_AMOUNT, BigDecimal::add);
    }
}
