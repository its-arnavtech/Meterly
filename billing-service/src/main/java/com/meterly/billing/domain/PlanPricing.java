package com.meterly.billing.domain;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The priced view of a plan: base fee, per-resource allowance and marginal tiers.
 *
 * <p>Construction validates the configuration so that every unit above the allowance is priced by
 * exactly one tier:
 * <ul>
 *   <li>base fee and unit prices are non-negative and no more precise than their columns;</li>
 *   <li>there is at least one tier, and the first tier starts at {@code includedUnits};</li>
 *   <li>tiers are contiguous (each {@code toUnit} is the next tier's {@code fromUnit});</li>
 *   <li>only the last tier is open-ended, and it must be.</li>
 * </ul>
 * Tiers are stored sorted by {@code fromUnit}.
 */
public record PlanPricing(
        String code,
        String name,
        BigDecimal basePrice,
        long includedUnits,
        String currency,
        List<Tier> tiers) {

    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    public PlanPricing {
        code = requireText(code, "Plan code");
        name = requireText(name, "Plan name");
        basePrice = Money.exactAmount(basePrice, "Plan base_price");
        if (includedUnits < 0) {
            throw new InvalidPlanException("Plan included_units must not be negative: " + includedUnits);
        }
        if (currency == null || !CURRENCY.matcher(currency).matches()) {
            throw new InvalidPlanException("Plan currency must be an ISO-4217 code: " + currency);
        }
        if (tiers == null || tiers.isEmpty()) {
            throw new InvalidPlanException("Plan " + code + " has no pricing tiers; units above the allowance would be free");
        }
        tiers = tiers.stream().sorted(Comparator.comparingLong(Tier::fromUnit)).toList();
        validateTierChain(code, includedUnits, tiers);
    }

    private static void validateTierChain(String code, long includedUnits, List<Tier> tiers) {
        if (tiers.getFirst().fromUnit() != includedUnits) {
            throw new InvalidPlanException("Plan " + code + ": first tier must start at included_units "
                    + includedUnits + " but starts at " + tiers.getFirst().fromUnit());
        }
        for (int i = 0; i < tiers.size() - 1; i++) {
            Tier current = tiers.get(i);
            Tier next = tiers.get(i + 1);
            if (current.isOpenEnded()) {
                throw new InvalidPlanException("Plan " + code + ": only the last tier may be open-ended");
            }
            if (current.toUnit() != next.fromUnit()) {
                throw new InvalidPlanException("Plan " + code + ": tiers must be contiguous, but ("
                        + current.fromUnit() + ", " + current.toUnit() + "] is followed by a tier starting at "
                        + next.fromUnit());
            }
        }
        if (!tiers.getLast().isOpenEnded()) {
            throw new InvalidPlanException("Plan " + code + ": the last tier must be open-ended (to_unit = null)");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidPlanException(field + " is required");
        }
        return value;
    }
}
