package com.meterly.billing.invoice;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@Component
public class TieredPriceCalculator {
    private static final int CENTS = 2;

    public BillingAmounts calculate(BigDecimal basePrice, long includedUnits, List<PricingTier> tiers, long totalUsage) {
        if (basePrice == null || tiers == null || basePrice.signum() < 0 || includedUnits < 0 || totalUsage < 0) {
            throw new IllegalArgumentException("Usage and prices cannot be negative or null");
        }
        BigDecimal base = money(basePrice);
        long billableHighWater = Math.max(includedUnits, totalUsage);
        List<TierCharge> charges = new ArrayList<>();
        BigDecimal total = base;
        for (PricingTier tier : tiers) {
            if (tier.fromUnit() <= includedUnits || tier.fromUnit() < 1 || tier.unitPrice() == null
                    || tier.unitPrice().signum() < 0 || (tier.toUnit() != null && tier.toUnit() < tier.fromUnit())) {
                throw new IllegalArgumentException("Invalid pricing tier");
            }
            long upper = tier.toUnit() == null ? billableHighWater : Math.min(billableHighWater, tier.toUnit());
            long quantity = Math.max(0, upper - tier.fromUnit() + 1);
            if (quantity > 0) {
                BigDecimal amount = money(tier.unitPrice().multiply(BigDecimal.valueOf(quantity)));
                charges.add(new TierCharge(tier.fromUnit(), tier.toUnit(), quantity, tier.unitPrice(), amount));
                total = total.add(amount);
            }
        }
        return new BillingAmounts(base, List.copyOf(charges), money(total));
    }

    private BigDecimal money(BigDecimal value) { return value.setScale(CENTS, RoundingMode.HALF_EVEN); }

    public record PricingTier(long fromUnit, Long toUnit, BigDecimal unitPrice) { }
    public record TierCharge(long fromUnit, Long toUnit, long quantity, BigDecimal unitPrice, BigDecimal amount) { }
    public record BillingAmounts(BigDecimal baseAmount, List<TierCharge> tierCharges, BigDecimal total) { }
}
