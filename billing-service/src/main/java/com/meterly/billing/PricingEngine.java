package com.meterly.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Pure, deterministic pricing. Each tier is rounded once, after multiplying exact decimal values. */
public final class PricingEngine {
    public record Tier(long fromUnit, Long toUnit, BigDecimal unitPrice) {}
    public record Plan(UUID id, String code, String name, BigDecimal basePrice, long includedUnits,
                       String currency, List<Tier> tiers) {}
    public record ResourceUsage(UUID id, String name, long units) {}
    public record Line(int lineNumber, String description, UUID resourceId, long quantity,
                       BigDecimal unitPrice, BigDecimal amount) {}
    public record Quote(List<Line> lines, BigDecimal total) {}

    public Quote price(Plan plan, List<ResourceUsage> usage) {
        validate(plan);
        List<Line> lines = new ArrayList<>();
        add(lines, plan.name() + " plan — base fee", null, 1, plan.basePrice());
        for (ResourceUsage resource : usage) {
            if (resource.units() < 0) throw new IllegalArgumentException("Usage cannot be negative");
            long included = Math.min(resource.units(), plan.includedUnits());
            add(lines, resource.name() + " — included", resource.id(), included, BigDecimal.ZERO);
            for (Tier tier : plan.tiers()) {
                long upper = tier.toUnit() == null ? resource.units() : Math.min(resource.units(), tier.toUnit());
                long quantity = Math.max(0, upper - tier.fromUnit());
                if (quantity > 0) add(lines, resource.name() + " — " + (tier.fromUnit() + 1) + "–"
                        + (tier.toUnit() == null ? "∞" : tier.toUnit()), resource.id(), quantity, tier.unitPrice());
            }
        }
        BigDecimal total = lines.stream().map(Line::amount).reduce(new BigDecimal("0.00"), BigDecimal::add);
        return new Quote(List.copyOf(lines), total);
    }

    private static void add(List<Line> lines, String description, UUID resource, long units, BigDecimal price) {
        lines.add(new Line(lines.size() + 1, description, resource, units, price.setScale(6),
                price.multiply(BigDecimal.valueOf(units)).setScale(2, RoundingMode.HALF_EVEN)));
    }

    private static void validate(Plan plan) {
        if (plan.basePrice().signum() < 0 || plan.includedUnits() < 0 || plan.tiers().isEmpty())
            throw new IllegalArgumentException("Invalid plan pricing");
        long next = plan.includedUnits();
        boolean ended = false;
        for (Tier tier : plan.tiers()) {
            if (ended || tier.fromUnit() != next || tier.unitPrice().signum() < 0
                    || (tier.toUnit() != null && tier.toUnit() <= next))
                throw new IllegalArgumentException("Pricing tiers must be contiguous and end in an unlimited tier");
            ended = tier.toUnit() == null;
            if (!ended) next = tier.toUnit();
        }
        if (!ended) throw new IllegalArgumentException("Missing unlimited pricing tier");
    }
}
