package com.meterly.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meterly.billing.run.PeriodCloseJob;
import com.meterly.billing.support.BillingFixtures;
import com.meterly.billing.support.BillingFixtures.Acme;
import com.meterly.billing.support.BillingIntegrationTest;
import com.meterly.common.security.InternalTokenFilter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.MeterNotFoundException;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Period close for every active tenant: the internal run-all endpoint and the scheduled job. One tenant's
 * failure (here a plan whose tiers leave a gap, which would under-bill) must not affect the others, and a
 * second pass must not produce second invoices.
 */
class PeriodCloseIT extends BillingIntegrationTest {

    private static final UUID BROKEN_PLAN = UUID.fromString("0a000000-0000-4000-8000-0000000000ff");
    private static final String AUGUST = "{\"period_start\":\"2026-08-01\"}";

    @Autowired
    private PeriodCloseJob periodClose;

    @Autowired
    private MeterRegistry meters;

    @AfterEach
    void removeBrokenPlan() {
        BillingFixtures.owner().update("DELETE FROM subscription WHERE plan_id = ?", BROKEN_PLAN);
        BillingFixtures.owner().update("DELETE FROM pricing_tier WHERE plan_id = ?", BROKEN_PLAN);
        BillingFixtures.owner().update("DELETE FROM plan WHERE id = ?", BROKEN_PLAN);
    }

    private ResultActions runAll(String body, String token) throws Exception {
        var request = post("/internal/v1/billing/run-all").contentType(MediaType.APPLICATION_JSON).content(body);
        if (token != null) {
            request = request.header(InternalTokenFilter.HEADER, token);
        }
        return mvc.perform(request);
    }

    /** A plan with a gap between the allowance and its first tier: units 1,001..2,000 would be unpriced. */
    private UUID tenantOnABrokenPlan() {
        BillingFixtures.owner().update("""
                INSERT INTO plan (id, code, name, base_price, included_units, sort_order)
                VALUES (?, 'BROKEN_TEST', 'Broken', 10.00, 1000, 99) ON CONFLICT DO NOTHING
                """, BROKEN_PLAN);
        BillingFixtures.owner().update("""
                INSERT INTO pricing_tier (plan_id, from_unit, to_unit, unit_price)
                VALUES (?, 2000, NULL, 0.001) ON CONFLICT DO NOTHING
                """, BROKEN_PLAN);
        UUID tenantId = BillingFixtures.tenant("Broken plan");
        UUID resource = BillingFixtures.resource(tenantId, "API calls");
        BillingFixtures.subscribe(tenantId, BROKEN_PLAN, LocalDate.of(2026, 8, 1));
        BillingFixtures.usage(tenantId, resource, 5_000, "2026-08-04T00:00:00Z");
        return tenantId;
    }

    private UUID tenantWithoutSubscription() {
        UUID tenantId = BillingFixtures.tenant("No subscription");
        BillingFixtures.resource(tenantId, "API calls");
        return tenantId;
    }

    @Test
    void billsEveryTenantAndIsolatesFailures() throws Exception {
        Acme acme = BillingFixtures.acme();
        UUID globex = BillingFixtures.globex();
        UUID unsubscribed = tenantWithoutSubscription();
        UUID broken = tenantOnABrokenPlan();
        double createdBefore = runCount("created");

        runAll(AUGUST, INTERNAL_TOKEN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period_start").value("2026-08-01"))
                .andExpect(jsonPath("$.created").value(2))
                .andExpect(jsonPath("$.already_invoiced").value(0))
                .andExpect(jsonPath("$.skipped_no_subscription").value(1))
                .andExpect(jsonPath("$.failed").value(1));

        assertThat(BillingFixtures.count("SELECT count(*) FROM invoice")).isEqualTo(2);
        assertThat(invoiceTotal(acme.tenantId())).isEqualTo("278.69");
        assertThat(invoiceTotal(globex)).isEqualTo("249.00");
        assertThat(BillingFixtures.count("SELECT count(*) FROM invoice WHERE tenant_id IN (?, ?)", unsubscribed, broken))
                .isZero();
        assertThat(BillingFixtures.count("SELECT count(*) FROM outbox_event WHERE event_type = 'invoice.issued'"))
                .isEqualTo(2);
        assertThat(runCount("created") - createdBefore).isEqualTo(2);

        // A second pass is a no-op for the tenants already invoiced.
        runAll(AUGUST, INTERNAL_TOKEN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(0))
                .andExpect(jsonPath("$.already_invoiced").value(2))
                .andExpect(jsonPath("$.skipped_no_subscription").value(1))
                .andExpect(jsonPath("$.failed").value(1));
        assertThat(BillingFixtures.count("SELECT count(*) FROM invoice")).isEqualTo(2);
    }

    @Test
    void theScheduledJobBillsThePreviousPeriod() {
        Acme acme = BillingFixtures.acme();
        BillingFixtures.globex();

        periodClose.closePreviousPeriod();

        assertThat(BillingFixtures.count("SELECT count(*) FROM invoice WHERE period_start = DATE '2026-08-01'"))
                .isEqualTo(2);
        assertThat(invoiceTotal(acme.tenantId())).isEqualTo("278.69");
    }

    @Test
    void runAllRejectsOpenAndMalformedPeriods() throws Exception {
        BillingFixtures.acme();

        runAll("{\"period_start\":\"2026-09-01\"}", INTERNAL_TOKEN)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PERIOD_NOT_CLOSED"));
        runAll("{\"period_start\":\"2026-08-02\"}", INTERNAL_TOKEN)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PERIOD"));
        assertThat(BillingFixtures.count("SELECT count(*) FROM invoice")).isZero();
    }

    @Test
    void runAllRequiresTheInternalToken() throws Exception {
        runAll(AUGUST, null).andExpect(status().isUnauthorized());
        runAll(AUGUST, "wrong-token-0123456789abcdef").andExpect(status().isUnauthorized());
        assertThat(BillingFixtures.count("SELECT count(*) FROM invoice")).isZero();
    }

    private String invoiceTotal(UUID tenantId) {
        return BillingFixtures.owner().queryForObject(
                "SELECT total_amount::text FROM invoice WHERE tenant_id = ?", String.class, tenantId);
    }

    private double runCount(String outcome) {
        try {
            return meters.get("meterly.billing.runs").tag("outcome", outcome).counter().count();
        } catch (MeterNotFoundException e) {
            return 0;
        }
    }
}
