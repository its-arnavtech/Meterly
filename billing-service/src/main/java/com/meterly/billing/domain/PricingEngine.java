package com.meterly.billing.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Prices a billing period. Pure and deterministic: no I/O, no clock, no framework.
 *
 * <p>Invoice layout:
 * <ol>
 *   <li>the plan base fee, once per period ({@code "Starter plan — base fee"});</li>
 *   <li>then, for every resource in the given order and <em>per resource</em> (the allowance and the
 *       tiers apply to each metered resource independently):
 *     <ul>
 *       <li>a zero-priced "included" line with the used part of the allowance
 *           ({@code "API calls — included (1–10,000)"}), present whenever the plan has an allowance;</li>
 *       <li>one line per tier that prices at least one unit, describing the cumulative unit range it
 *           covers ({@code "API calls — 10,001–100,000"}).</li>
 *     </ul>
 *   </li>
 * </ol>
 * Every line amount is rounded once (HALF_EVEN, 2 dp); the total is the sum of the rounded lines.
 * For each resource the tier quantities add up to {@code max(0, total - includedUnits)}.
 */
public final class PricingEngine {

    static final String EM_DASH = "—";
    static final String EN_DASH = "–";

    public InvoiceDraft price(PlanPricing plan, List<ResourceUsage> usage) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(usage, "usage");
        List<LineItem> lines = new ArrayList<>();
        lines.add(LineItem.priced(1, baseFeeDescription(plan), null, 1, plan.basePrice()));
        for (ResourceUsage resource : usage) {
            addResourceLines(plan, resource, lines);
        }
        return InvoiceDraft.of(plan, lines);
    }

    private static void addResourceLines(PlanPricing plan, ResourceUsage resource, List<LineItem> lines) {
        long total = resource.totalUnits();
        if (plan.includedUnits() > 0) {
            long includedUsed = Math.min(total, plan.includedUnits());
            lines.add(LineItem.priced(lines.size() + 1, includedDescription(resource, includedUsed),
                    resource.resourceId(), includedUsed, Money.ZERO_UNIT_PRICE));
        }
        for (Tier tier : plan.tiers()) {
            long quantity = tier.unitsIn(total);
            if (quantity > 0) {
                long firstUnit = tier.fromUnit() + 1;
                long lastUnit = tier.fromUnit() + quantity;
                lines.add(LineItem.priced(lines.size() + 1, rangeDescription(resource, firstUnit, lastUnit),
                        resource.resourceId(), quantity, tier.unitPrice()));
            }
        }
    }

    static String baseFeeDescription(PlanPricing plan) {
        return plan.name() + " plan " + EM_DASH + " base fee";
    }

    static String includedDescription(ResourceUsage resource, long includedUsed) {
        String used = includedUsed == 0 ? "no usage" : "1" + EN_DASH + units(includedUsed);
        return resource.resourceName() + " " + EM_DASH + " included (" + used + ")";
    }

    static String rangeDescription(ResourceUsage resource, long firstUnit, long lastUnit) {
        return resource.resourceName() + " " + EM_DASH + " " + units(firstUnit) + EN_DASH + units(lastUnit);
    }

    private static String units(long value) {
        return String.format(Locale.US, "%,d", value);
    }
}
