package com.meterly.billing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * The money rules of the billing domain, in one place.
 *
 * <ul>
 *   <li>Unit prices carry {@value #UNIT_PRICE_SCALE} decimals and amounts {@value #AMOUNT_SCALE}
 *       decimals, matching the {@code numeric(19,6)} / {@code numeric(19,2)} database columns.</li>
 *   <li>Configured prices are normalized <em>exactly</em>: a price with more precision than its column
 *       is rejected rather than silently rounded.</li>
 *   <li>A line amount is {@code unitPrice x quantity}, computed exactly and rounded once, {@code HALF_EVEN}
 *       (banker's rounding), to the cent. Totals are sums of already-rounded line amounts.</li>
 * </ul>
 */
public final class Money {

    public static final int UNIT_PRICE_SCALE = 6;
    public static final int AMOUNT_SCALE = 2;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;
    public static final BigDecimal ZERO_AMOUNT = BigDecimal.ZERO.setScale(AMOUNT_SCALE);
    public static final BigDecimal ZERO_UNIT_PRICE = BigDecimal.ZERO.setScale(UNIT_PRICE_SCALE);

    private Money() {
    }

    /** {@code unitPrice x quantity}, rounded once, HALF_EVEN, to the cent. */
    public static BigDecimal lineAmount(BigDecimal unitPrice, long quantity) {
        Objects.requireNonNull(unitPrice, "unitPrice");
        return unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(AMOUNT_SCALE, ROUNDING);
    }

    /** Normalizes a configured unit price to scale 6 without rounding; rejects negative or over-precise values. */
    public static BigDecimal exactUnitPrice(BigDecimal value, String field) {
        return exact(value, UNIT_PRICE_SCALE, field);
    }

    /** Normalizes a configured amount (e.g. a base fee) to scale 2 without rounding. */
    public static BigDecimal exactAmount(BigDecimal value, String field) {
        return exact(value, AMOUNT_SCALE, field);
    }

    private static BigDecimal exact(BigDecimal value, int scale, String field) {
        if (value == null) {
            throw new InvalidPlanException(field + " is required");
        }
        if (value.signum() < 0) {
            throw new InvalidPlanException(field + " must not be negative: " + value.toPlainString());
        }
        try {
            return value.setScale(scale, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new InvalidPlanException(field + " has more than " + scale + " decimal places: "
                    + value.toPlainString());
        }
    }
}
