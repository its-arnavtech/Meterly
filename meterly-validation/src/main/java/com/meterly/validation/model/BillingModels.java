package com.meterly.validation.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Response shapes from tenant-service's plan catalog and billing-service (docs/api-contract.md §1, §4). */
public final class BillingModels {
    private BillingModels() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PricingTier(long fromUnit, Long toUnit, BigDecimal unitPrice) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Plan(UUID id, String code, String name, BigDecimal basePrice, long includedUnits,
                        String currency, List<PricingTier> tiers) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PlansResponse(List<Plan> items) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PlanRef(String code, String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SubscriptionInfo(UUID id, Plan plan, LocalDate startDate, LocalDate endDate, String status) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SubscriptionResponse(SubscriptionInfo current, SubscriptionInfo scheduled) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LineItem(int lineNumber, String description, UUID resourceId, long quantity,
                            BigDecimal unitPrice, BigDecimal amount) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Invoice(UUID id, String invoiceNumber, LocalDate periodStart, LocalDate periodEnd,
                           String status, String currency, BigDecimal totalAmount, Instant issuedAt,
                           PlanRef plan, List<LineItem> lineItems) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InvoiceSummary(UUID id, String invoiceNumber, LocalDate periodStart, LocalDate periodEnd,
                                  String status, String currency, BigDecimal totalAmount, Instant issuedAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InvoicesResponse(List<InvoiceSummary> items) {
    }
}
