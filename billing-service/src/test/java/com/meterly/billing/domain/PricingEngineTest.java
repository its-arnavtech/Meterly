package com.meterly.billing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * PDD section 12 "Unit — billing": tier-boundary edge cases against hand-calculated totals. The plans
 * mirror the catalog in V4__plan_catalog.sql.
 */
class PricingEngineTest {

    static final PlanPricing STARTER = new PlanPricing("STARTER", "Starter", new BigDecimal("19.00"), 10_000, "USD",
            List.of(Tier.bounded(10_000, 100_000, "0.002000"), Tier.openEnded(100_000, "0.001500")));
    static final PlanPricing GROWTH = new PlanPricing("GROWTH", "Growth", new BigDecimal("99.00"), 100_000, "USD",
            List.of(Tier.bounded(100_000, 1_000_000, "0.001000"), Tier.openEnded(1_000_000, "0.000700")));

    static final UUID API = UUID.fromString("a1111111-0000-4000-8000-000000000001");
    static final UUID HOOKS = UUID.fromString("a1111111-0000-4000-8000-000000000002");

    private final PricingEngine engine = new PricingEngine();

    private InvoiceDraft priceApiCalls(PlanPricing plan, long units) {
        return engine.price(plan, List.of(new ResourceUsage(API, "API calls", units)));
    }

    @Nested
    class HandCalculatedTotals {

        @Test
        void zeroUsageBillsOnlyTheBaseFee() {
            InvoiceDraft draft = priceApiCalls(STARTER, 0);

            assertThat(draft.totalAmount()).isEqualTo("19.00");
            assertThat(draft.lineItems()).extracting(LineItem::description, LineItem::quantity, LineItem::amount)
                    .containsExactly(
                            tuple("Starter plan — base fee", 1L, new BigDecimal("19.00")),
                            tuple("API calls — included (no usage)", 0L, new BigDecimal("0.00")));
        }

        @Test
        void usageExactlyAtIncludedUnitsIsFree() {
            InvoiceDraft draft = priceApiCalls(STARTER, 10_000);

            assertThat(draft.totalAmount()).isEqualTo("19.00");
            assertThat(draft.lineItems()).extracting(LineItem::description, LineItem::quantity)
                    .containsExactly(
                            tuple("Starter plan — base fee", 1L),
                            tuple("API calls — included (1–10,000)", 10_000L));
        }

        @Test
        void oneUnitOverTheAllowanceIsPricedByTheFirstTier() {
            // 1 x 0.002 = 0.002 -> 0.00 (HALF_EVEN); 3 x 0.002 = 0.006 -> 0.01
            assertThat(priceApiCalls(STARTER, 10_001).lineItems().getLast())
                    .extracting(LineItem::description, LineItem::quantity, LineItem::unitPrice, LineItem::amount)
                    .containsExactly("API calls — 10,001–10,001", 1L, new BigDecimal("0.002000"), new BigDecimal("0.00"));
            assertThat(priceApiCalls(STARTER, 10_001).totalAmount()).isEqualTo("19.00");
            assertThat(priceApiCalls(STARTER, 10_003).totalAmount()).isEqualTo("19.01");
        }

        @Test
        void usageExactlyAtATierBoundaryDoesNotTouchTheNextTier() {
            // 19.00 + 90,000 x 0.002 = 19.00 + 180.00
            InvoiceDraft draft = priceApiCalls(STARTER, 100_000);

            assertThat(draft.totalAmount()).isEqualTo("199.00");
            assertThat(draft.lineItems()).hasSize(3);
            assertThat(draft.lineItems().getLast())
                    .extracting(LineItem::description, LineItem::quantity, LineItem::amount)
                    .containsExactly("API calls — 10,001–100,000", 90_000L, new BigDecimal("180.00"));
        }

        @Test
        void usageSpanningTwoTiersMatchesTheContractExample() {
            // 19.00 + 90,000 x 0.002 + 50,000 x 0.0015 = 19.00 + 180.00 + 75.00
            InvoiceDraft draft = priceApiCalls(STARTER, 150_000);

            assertThat(draft.totalAmount()).isEqualTo("274.00");
            assertThat(draft.lineItems())
                    .extracting(LineItem::lineNumber, LineItem::description, LineItem::resourceId,
                            LineItem::quantity, LineItem::unitPrice, LineItem::amount)
                    .containsExactly(
                            tuple(1, "Starter plan — base fee", null, 1L, new BigDecimal("19.000000"), new BigDecimal("19.00")),
                            tuple(2, "API calls — included (1–10,000)", API, 10_000L, new BigDecimal("0.000000"), new BigDecimal("0.00")),
                            tuple(3, "API calls — 10,001–100,000", API, 90_000L, new BigDecimal("0.002000"), new BigDecimal("180.00")),
                            tuple(4, "API calls — 100,001–150,000", API, 50_000L, new BigDecimal("0.001500"), new BigDecimal("75.00")));
        }

        @Test
        void allowanceAndTiersApplyPerResourceAndTheBaseFeeOnce() {
            // Demo tenant Acme: 19.00 + (180.00 + 75.00) + 2,345 x 0.002 (= 4.69) = 278.69
            InvoiceDraft draft = engine.price(STARTER, List.of(
                    new ResourceUsage(API, "API calls", 150_000),
                    new ResourceUsage(HOOKS, "Webhooks delivered", 12_345)));

            assertThat(draft.totalAmount()).isEqualTo("278.69");
            assertThat(draft.lineItems())
                    .extracting(LineItem::lineNumber, LineItem::description, LineItem::resourceId, LineItem::quantity, LineItem::amount)
                    .containsExactly(
                            tuple(1, "Starter plan — base fee", null, 1L, new BigDecimal("19.00")),
                            tuple(2, "API calls — included (1–10,000)", API, 10_000L, new BigDecimal("0.00")),
                            tuple(3, "API calls — 10,001–100,000", API, 90_000L, new BigDecimal("180.00")),
                            tuple(4, "API calls — 100,001–150,000", API, 50_000L, new BigDecimal("75.00")),
                            tuple(5, "Webhooks delivered — included (1–10,000)", HOOKS, 10_000L, new BigDecimal("0.00")),
                            tuple(6, "Webhooks delivered — 10,001–12,345", HOOKS, 2_345L, new BigDecimal("4.69")));
        }

        @Test
        void growthPlanDemoTenant() {
            // Demo tenant Globex: 99.00 + 150,000 x 0.001 = 249.00
            InvoiceDraft draft = priceApiCalls(GROWTH, 250_000);

            assertThat(draft.totalAmount()).isEqualTo("249.00");
            assertThat(draft.planCode()).isEqualTo("GROWTH");
            assertThat(draft.lineItems()).extracting(LineItem::description, LineItem::quantity, LineItem::amount)
                    .containsExactly(
                            tuple("Growth plan — base fee", 1L, new BigDecimal("99.00")),
                            tuple("API calls — included (1–100,000)", 100_000L, new BigDecimal("0.00")),
                            tuple("API calls — 100,001–250,000", 150_000L, new BigDecimal("150.00")));
        }

        @Test
        void aTrillionUnitsArePricedExactly() {
            // 19.00 + 180.00 + (1,000,000,000,000 - 100,000) x 0.0015 = 19.00 + 180.00 + 1,499,999,850.00
            InvoiceDraft draft = priceApiCalls(STARTER, 1_000_000_000_000L);

            assertThat(draft.totalAmount()).isEqualTo("1500000049.00");
            assertThat(draft.lineItems().getLast())
                    .extracting(LineItem::description, LineItem::quantity, LineItem::amount)
                    .containsExactly("API calls — 100,001–1,000,000,000,000", 999_999_900_000L,
                            new BigDecimal("1499999850.00"));
        }
    }

    @Nested
    class Rounding {

        private InvoiceDraft priceAt(String unitPrice, long units) {
            PlanPricing plan = new PlanPricing("TEST", "Test", new BigDecimal("0.00"), 0, "USD",
                    List.of(Tier.openEnded(0, unitPrice)));
            return engine.price(plan, List.of(new ResourceUsage(API, "Calls", units)));
        }

        @ParameterizedTest(name = "{1} x {0} = {2}")
        @CsvSource({
                "0.001, 5,    0.00",   // 0.005 -> round half to even (0)
                "0.001, 15,   0.02",   // 0.015 -> 0.02 (2 is even)
                "0.001, 25,   0.02",   // 0.025 -> 0.02 (2 is even), HALF_UP would give 0.03
                "0.001, 35,   0.04",   // 0.035 -> 0.04
                "0.002, 2347, 4.69",   // 4.694 -> 4.69
                "0.000700, 1, 0.00",
        })
        void lineAmountsRoundHalfEvenToTheCentExactlyOnce(String unitPrice, long units, String expected) {
            InvoiceDraft draft = priceAt(unitPrice, units);

            assertThat(draft.lineItems().getLast().amount()).isEqualTo(expected);
            assertThat(draft.totalAmount()).isEqualTo(expected);
        }

        @Test
        void totalIsTheSumOfRoundedLinesNotTheRoundedSumOfExactAmounts() {
            // Each resource: 5 x 0.001 = 0.005 -> 0.00. Summing exact amounts first would give 0.01.
            PlanPricing plan = new PlanPricing("TEST", "Test", new BigDecimal("0.00"), 0, "USD",
                    List.of(Tier.openEnded(0, "0.001")));
            InvoiceDraft draft = engine.price(plan, List.of(
                    new ResourceUsage(API, "A", 5), new ResourceUsage(HOOKS, "B", 5)));

            assertThat(draft.totalAmount()).isEqualTo("0.00");
        }

        @Test
        void everyLineCarriesContractScales() {
            InvoiceDraft draft = priceApiCalls(STARTER, 150_000);

            assertThat(draft.lineItems()).allSatisfy(line -> {
                assertThat(line.unitPrice().scale()).isEqualTo(6);
                assertThat(line.amount().scale()).isEqualTo(2);
            });
            assertThat(draft.lineItems().getFirst().unitPrice().toPlainString()).isEqualTo("19.000000");
            assertThat(draft.totalAmount().toPlainString()).isEqualTo("274.00");
        }
    }

    @Nested
    class Invariants {

        @Test
        void tierQuantitiesAddUpToUsageAboveTheAllowance() {
            Random random = new Random(20260922L);
            LongStream boundaries = LongStream.of(0, 1, 9_999, 10_000, 10_001, 99_999, 100_000, 100_001,
                    999_999, 1_000_000, 1_000_001, 10_000_000_000L);
            LongStream randomTotals = random.longs(500, 0, 5_000_000);

            LongStream.concat(boundaries, randomTotals).forEach(total -> {
                for (PlanPricing plan : List.of(STARTER, GROWTH)) {
                    List<LineItem> lines = priceApiCalls(plan, total).lineItems();
                    LineItem included = lines.get(1);
                    long tierUnits = lines.subList(2, lines.size()).stream().mapToLong(LineItem::quantity).sum();

                    assertThat(included.quantity()).as("included, total=%d", total)
                            .isEqualTo(Math.min(total, plan.includedUnits()));
                    assertThat(tierUnits).as("tier units, total=%d", total)
                            .isEqualTo(Math.max(0, total - plan.includedUnits()));
                    assertThat(lines).extracting(LineItem::lineNumber)
                            .containsExactlyElementsOf(IntStream.rangeClosed(1, lines.size()).boxed().toList());
                    assertThat(lines.subList(2, lines.size())).allSatisfy(line -> assertThat(line.quantity()).isPositive());
                }
            });
        }

        @Test
        void planWithoutAllowanceHasNoIncludedLine() {
            PlanPricing payAsYouGo = new PlanPricing("PAYG", "Pay as you go", new BigDecimal("0"), 0, "USD",
                    List.of(Tier.openEnded(0, "0.01")));

            InvoiceDraft draft = engine.price(payAsYouGo, List.of(new ResourceUsage(API, "API calls", 250)));

            assertThat(draft.lineItems()).extracting(LineItem::description)
                    .containsExactly("Pay as you go plan — base fee", "API calls — 1–250");
            assertThat(draft.totalAmount()).isEqualTo("2.50");
        }

        @Test
        void noResourcesStillBillsTheBaseFee() {
            assertThat(engine.price(STARTER, List.of()).totalAmount()).isEqualTo("19.00");
        }
    }
}
