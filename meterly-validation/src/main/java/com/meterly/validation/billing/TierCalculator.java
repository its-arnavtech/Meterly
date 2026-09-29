package com.meterly.validation.billing;

import com.meterly.validation.model.BillingModels.Plan;
import com.meterly.validation.model.BillingModels.PricingTier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * Independent re-implementation of billing-service's pricing algorithm (docs/api-contract.md §4,
 * step 3), built only from {@code GET /v1/plans} and known usage — it shares no code with
 * billing-service, so agreement with the real invoice is a genuine cross-check, not a tautology.
 *
 * <p>Tier semantics: a tier prices cumulative units in {@code (from_unit, to_unit]};
 * {@code units_in_tier = max(0, min(total, to_unit ?? ∞) − from_unit)}. A plan's tiers already start
 * at {@code included_units} (proven by {@code RowLevelSecurityIT.seededPlanTiersAreContiguousAndStartAtTheIncludedUnits}),
 * so included units fall out of the formula automatically without special-casing them. Each tier's
 * charge is rounded {@link RoundingMode#HALF_EVEN} to 2 decimal places once, then summed — never
 * rounded once at the end — matching the server exactly.
 */
public final class TierCalculator {

    private TierCalculator() {
    }

    /** The charge for one metered resource's total usage in the period, against one plan's tiers. */
    public static BigDecimal chargeForResource(long totalUnits, List<PricingTier> tiers) {
        BigDecimal total = BigDecimal.ZERO;
        for (PricingTier tier : tiers) {
            long upper = tier.toUnit() == null ? Long.MAX_VALUE : tier.toUnit();
            long unitsInTier = Math.max(0L, Math.min(totalUnits, upper) - tier.fromUnit());
            if (unitsInTier <= 0) {
                continue;
            }
            BigDecimal amount = tier.unitPrice()
                    .multiply(BigDecimal.valueOf(unitsInTier))
                    .setScale(2, RoundingMode.HALF_EVEN);
            total = total.add(amount);
        }
        return total;
    }

    /**
     * The full invoice total for a plan: the base fee (once) plus each resource's tiered charge.
     *
     * @param usageByResource total units per metered resource for the period, from the ledger
     *                        ({@code GET /v1/usage/timeseries} summed, or the live counter for an open period)
     */
    public static BigDecimal invoiceTotal(Plan plan, Map<?, Long> usageByResource) {
        BigDecimal total = plan.basePrice().setScale(2, RoundingMode.HALF_EVEN);
        for (long units : usageByResource.values()) {
            total = total.add(chargeForResource(units, plan.tiers()));
        }
        return total;
    }
}
