package com.meterly.billing.domain;

import java.math.BigDecimal;

/**
 * One marginal price band of a plan. The tier prices the <em>cumulative</em> units of a resource that
 * fall into the half-open range {@code (fromUnit, toUnit]}:
 * <pre>
 *   units_in_tier = max(0, min(total, toUnit ?? infinity) - fromUnit)
 * </pre>
 *
 * @param fromUnit  exclusive lower bound (the units already covered by lower tiers / the allowance)
 * @param toUnit    inclusive upper bound, or {@code null} for the open-ended last tier
 * @param unitPrice price per unit, normalized to scale 6
 */
public record Tier(long fromUnit, Long toUnit, BigDecimal unitPrice) {

    public Tier {
        if (fromUnit < 0) {
            throw new InvalidPlanException("Tier from_unit must not be negative: " + fromUnit);
        }
        if (toUnit != null && toUnit <= fromUnit) {
            throw new InvalidPlanException("Tier to_unit must be greater than from_unit: (" + fromUnit + ", " + toUnit + "]");
        }
        unitPrice = Money.exactUnitPrice(unitPrice, "Tier unit_price");
    }

    public static Tier bounded(long fromUnit, long toUnit, String unitPrice) {
        return new Tier(fromUnit, toUnit, new BigDecimal(unitPrice));
    }

    public static Tier openEnded(long fromUnit, String unitPrice) {
        return new Tier(fromUnit, null, new BigDecimal(unitPrice));
    }

    public boolean isOpenEnded() {
        return toUnit == null;
    }

    /** How many of {@code totalUnits} cumulative units fall into this tier. */
    public long unitsIn(long totalUnits) {
        long upper = isOpenEnded() ? totalUnits : Math.min(totalUnits, toUnit);
        return Math.max(0, upper - fromUnit);
    }
}
