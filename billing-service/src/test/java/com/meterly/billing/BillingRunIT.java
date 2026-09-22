package com.meterly.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meterly.billing.run.BillingRunResult;
import com.meterly.billing.run.BillingService;
import com.meterly.billing.support.BillingFixtures;
import com.meterly.billing.support.BillingFixtures.Acme;
import com.meterly.billing.support.BillingIntegrationTest;
import com.meterly.common.billing.BillingPeriod;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * {@code POST /v1/billing/run} end to end: cent-accurate invoice from the seeded ledger, idempotent
 * re-runs (including two concurrent runs), the contract's error codes and the rows the run must write.
 */
class BillingRunIT extends BillingIntegrationTest {

    private static final String AUGUST = "{\"period_start\":\"2026-08-01\"}";
    private static final BillingPeriod AUGUST_PERIOD = BillingPeriod.startingOn(LocalDate.of(2026, 8, 1));

    @Autowired
    private BillingService billing;

    @Autowired
    private ObjectMapper objectMapper;

    private ResultActions run(UUID tenantId, String body) throws Exception {
        var request = post("/v1/billing/run").header(HttpHeaders.AUTHORIZATION, admin(tenantId));
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }

    @Test
    void billsTheSeededLedgerToTheCentWithTheContractsLineItems() throws Exception {
        Acme acme = BillingFixtures.acme();

        run(acme.tenantId(), AUGUST)
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, containsString("/v1/invoices/")))
                .andExpect(jsonPath("$.invoice_number").value("INV-202608-000001"))
                .andExpect(jsonPath("$.period_start").value("2026-08-01"))
                .andExpect(jsonPath("$.period_end").value("2026-09-01"))
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.total_amount").value("278.69"))
                .andExpect(jsonPath("$.plan.code").value("STARTER"))
                .andExpect(jsonPath("$.plan.name").value("Starter"))
                .andExpect(jsonPath("$.line_items.length()").value(6))
                .andExpect(jsonPath("$.line_items[0].line_number").value(1))
                .andExpect(jsonPath("$.line_items[0].description").value("Starter plan — base fee"))
                .andExpect(jsonPath("$.line_items[0].resource_id").value(nullValue()))
                .andExpect(jsonPath("$.line_items[0].quantity").value(1))
                .andExpect(jsonPath("$.line_items[0].unit_price").value("19.000000"))
                .andExpect(jsonPath("$.line_items[0].amount").value("19.00"))
                .andExpect(jsonPath("$.line_items[1].description").value("API calls — included (1–10,000)"))
                .andExpect(jsonPath("$.line_items[1].resource_id").value(acme.apiCalls().toString()))
                .andExpect(jsonPath("$.line_items[1].quantity").value(10000))
                .andExpect(jsonPath("$.line_items[1].unit_price").value("0.000000"))
                .andExpect(jsonPath("$.line_items[1].amount").value("0.00"))
                .andExpect(jsonPath("$.line_items[2].description").value("API calls — 10,001–100,000"))
                .andExpect(jsonPath("$.line_items[2].quantity").value(90000))
                .andExpect(jsonPath("$.line_items[2].unit_price").value("0.002000"))
                .andExpect(jsonPath("$.line_items[2].amount").value("180.00"))
                .andExpect(jsonPath("$.line_items[3].description").value("API calls — 100,001–150,000"))
                .andExpect(jsonPath("$.line_items[3].quantity").value(50000))
                .andExpect(jsonPath("$.line_items[3].amount").value("75.00"))
                .andExpect(jsonPath("$.line_items[4].description").value("Webhooks delivered — included (1–10,000)"))
                .andExpect(jsonPath("$.line_items[5].description").value("Webhooks delivered — 10,001–12,345"))
                .andExpect(jsonPath("$.line_items[5].quantity").value(2345))
                .andExpect(jsonPath("$.line_items[5].amount").value("4.69"))
                // Money is JSON strings, never numbers, and the base fee line carries an explicit null resource.
                .andExpect(content().string(containsString("\"total_amount\":\"278.69\"")))
                .andExpect(content().string(containsString("\"resource_id\":null")));
    }

    @Test
    void writesAggregatesLineItemsAndOneOutboxEventInTheSameTransaction() throws Exception {
        Acme acme = BillingFixtures.acme();

        run(acme.tenantId(), AUGUST).andExpect(status().isCreated());

        UUID tenantId = acme.tenantId();
        assertThat(BillingFixtures.count("SELECT count(*) FROM invoice WHERE tenant_id = ?", tenantId)).isEqualTo(1);
        assertThat(BillingFixtures.count("SELECT count(*) FROM invoice_line_item WHERE tenant_id = ?", tenantId))
                .isEqualTo(6);
        assertThat(BillingFixtures.count("""
                SELECT count(*) FROM usage_period_aggregate
                WHERE tenant_id = ? AND period_start = DATE '2026-08-01' AND period_end = DATE '2026-09-01'
                """, tenantId)).isEqualTo(2);
        assertThat(BillingFixtures.owner().queryForObject("""
                SELECT total_units FROM usage_period_aggregate WHERE tenant_id = ? AND resource_id = ?
                """, Long.class, tenantId, acme.apiCalls())).isEqualTo(150_000L);
        assertThat(BillingFixtures.count("""
                SELECT count(*) FROM outbox_event
                WHERE tenant_id = ? AND event_type = 'invoice.issued' AND published_at IS NULL
                """, tenantId)).isEqualTo(1);
        assertThat(BillingFixtures.owner().queryForObject(
                "SELECT payload->>'total_amount' FROM outbox_event WHERE tenant_id = ?", String.class, tenantId))
                .isEqualTo("278.69");
    }

    @Test
    void reRunningAClosedPeriodReturnsTheSameInvoiceAndWritesNothing() throws Exception {
        Acme acme = BillingFixtures.acme();
        JsonNode first = body(run(acme.tenantId(), AUGUST).andExpect(status().isCreated()));

        JsonNode second = body(run(acme.tenantId(), AUGUST)
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION)));

        assertThat(second).isEqualTo(first);
        assertThat(BillingFixtures.count("SELECT count(*) FROM invoice WHERE tenant_id = ?", acme.tenantId()))
                .isEqualTo(1);
        assertThat(BillingFixtures.count("SELECT count(*) FROM invoice_line_item WHERE tenant_id = ?", acme.tenantId()))
                .isEqualTo(6);
        assertThat(BillingFixtures.count("SELECT count(*) FROM outbox_event WHERE tenant_id = ?", acme.tenantId()))
                .isEqualTo(1);
    }

    @Test
    void concurrentRunsProduceExactlyOneInvoice() throws Exception {
        Acme acme = BillingFixtures.acme();
        int threads = 4;
        CyclicBarrier startTogether = new CyclicBarrier(threads);
        List<Callable<BillingRunResult>> runs = java.util.Collections.nCopies(threads, () -> {
            startTogether.await();
            return billing.run(acme.tenantId(), AUGUST_PERIOD);
        });

        List<BillingRunResult> results;
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<BillingRunResult>> futures = pool.invokeAll(runs);
            results = futures.stream().map(BillingRunIT::value).toList();
        }

        assertThat(results).filteredOn(BillingRunResult.Created.class::isInstance).hasSize(1);
        assertThat(results).extracting(result -> result.invoice().id()).containsOnly(results.getFirst().invoice().id());
        assertThat(BillingFixtures.count("SELECT count(*) FROM invoice WHERE tenant_id = ?", acme.tenantId()))
                .isEqualTo(1);
        assertThat(BillingFixtures.count("SELECT count(*) FROM invoice_line_item WHERE tenant_id = ?", acme.tenantId()))
                .isEqualTo(6);
        assertThat(BillingFixtures.count("SELECT count(*) FROM outbox_event WHERE tenant_id = ?", acme.tenantId()))
                .isEqualTo(1);
    }

    @Test
    void withoutABodyTheDefaultPeriodIsTheMostRecentlyClosedMonth() throws Exception {
        Acme acme = BillingFixtures.acme();

        run(acme.tenantId(), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.period_start").value("2026-08-01"))
                .andExpect(jsonPath("$.total_amount").value("278.69"));
    }

    @Test
    void anOpenPeriodCannotBeInvoiced() throws Exception {
        Acme acme = BillingFixtures.acme();

        run(acme.tenantId(), "{\"period_start\":\"2026-09-01\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("PERIOD_NOT_CLOSED"));
        assertThat(BillingFixtures.count("SELECT count(*) FROM invoice")).isZero();
    }

    @Test
    void aPeriodThatIsNotTheFirstOfAMonthIsRejected() throws Exception {
        Acme acme = BillingFixtures.acme();

        run(acme.tenantId(), "{\"period_start\":\"2026-08-15\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PERIOD"))
                .andExpect(jsonPath("$.errors[0].field").value("period_start"));
        run(acme.tenantId(), "{\"period_start\":\"last month\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PERIOD"));
    }

    @Test
    void aTenantWithoutASubscriptionCoveringThePeriodIsNotInvoiced() throws Exception {
        UUID tenantId = BillingFixtures.tenant("No subscription");
        BillingFixtures.resource(tenantId, "API calls");
        // Subscription starts after the period it would have to cover.
        BillingFixtures.subscribe(tenantId, BillingFixtures.STARTER, LocalDate.of(2026, 9, 1));

        run(tenantId, AUGUST)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_SUBSCRIPTION"));
        assertThat(BillingFixtures.count("SELECT count(*) FROM invoice")).isZero();
    }

    @Test
    void billsActiveResourcesWithoutUsageAndArchivedResourcesThatHadUsage() throws Exception {
        UUID tenantId = BillingFixtures.tenant("Mixed resources");
        UUID idle = BillingFixtures.resource(tenantId, "Idle resource");
        UUID archived = BillingFixtures.resource(tenantId, "Retired resource", "ARCHIVED");
        UUID gone = BillingFixtures.resource(tenantId, "Zzz forgotten resource", "ARCHIVED");
        BillingFixtures.subscribe(tenantId, BillingFixtures.STARTER, LocalDate.of(2026, 8, 1));
        BillingFixtures.usage(tenantId, archived, 12_000, "2026-08-05T00:00:00Z");

        run(tenantId, AUGUST)
                .andExpect(status().isCreated())
                // 19.00 + 2,000 x 0.002 = 19.00 + 4.00
                .andExpect(jsonPath("$.total_amount").value("23.00"))
                .andExpect(jsonPath("$.line_items.length()").value(4))
                .andExpect(jsonPath("$.line_items[1].description").value("Idle resource — included (no usage)"))
                .andExpect(jsonPath("$.line_items[1].resource_id").value(idle.toString()))
                .andExpect(jsonPath("$.line_items[2].description").value("Retired resource — included (1–10,000)"))
                .andExpect(jsonPath("$.line_items[3].description").value("Retired resource — 10,001–12,000"))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString(gone.toString()))));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static BillingRunResult value(Future<BillingRunResult> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }
}
