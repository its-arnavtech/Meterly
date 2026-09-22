package com.meterly.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meterly.billing.run.BillingRunResult;
import com.meterly.billing.run.BillingService;
import com.meterly.billing.support.BillingFixtures;
import com.meterly.billing.support.BillingFixtures.Acme;
import com.meterly.billing.support.BillingIntegrationTest;
import com.meterly.common.billing.BillingPeriod;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

/**
 * The invoice read API and the preview: tenant isolation (RLS), role checks and the preview's
 * "priced but never persisted" contract.
 */
class InvoiceApiIT extends BillingIntegrationTest {

    private static final BillingPeriod AUGUST = BillingPeriod.startingOn(LocalDate.of(2026, 8, 1));

    @Autowired
    private BillingService billing;

    private UUID invoiceOf(UUID tenantId) {
        BillingRunResult result = billing.run(tenantId, AUGUST);
        return result.invoice().id();
    }

    @Test
    void listsOnlyTheCallersInvoicesNewestPeriodFirst() throws Exception {
        Acme acme = BillingFixtures.acme();
        UUID globex = BillingFixtures.globex();
        invoiceOf(acme.tenantId());
        invoiceOf(globex);
        billing.run(acme.tenantId(), BillingPeriod.startingOn(LocalDate.of(2026, 7, 1)));

        mvc.perform(get("/v1/invoices").header(HttpHeaders.AUTHORIZATION, admin(acme.tenantId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].period_start").value("2026-08-01"))
                .andExpect(jsonPath("$.items[0].total_amount").value("278.69"))
                .andExpect(jsonPath("$.items[0].status").value("ISSUED"))
                .andExpect(jsonPath("$.items[0].issued_at").value("2026-09-15T12:00:00Z"))
                .andExpect(jsonPath("$.items[1].period_start").value("2026-07-01"))
                // July has only 5,000 units, inside the allowance: base fee only.
                .andExpect(jsonPath("$.items[1].total_amount").value("19.00"));

        mvc.perform(get("/v1/invoices").header(HttpHeaders.AUTHORIZATION, admin(globex)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].total_amount").value("249.00"));
    }

    @Test
    void returnsInvoiceDetailWithItsPlanSnapshotAndLineItems() throws Exception {
        Acme acme = BillingFixtures.acme();
        UUID invoiceId = invoiceOf(acme.tenantId());

        mvc.perform(get("/v1/invoices/{id}", invoiceId).header(HttpHeaders.AUTHORIZATION, admin(acme.tenantId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(invoiceId.toString()))
                .andExpect(jsonPath("$.plan.code").value("STARTER"))
                .andExpect(jsonPath("$.total_amount").value("278.69"))
                .andExpect(jsonPath("$.line_items.length()").value(6));
    }

    @Test
    void anotherTenantsInvoiceIsIndistinguishableFromAnUnknownOne() throws Exception {
        Acme acme = BillingFixtures.acme();
        UUID globex = BillingFixtures.globex();
        UUID globexInvoice = invoiceOf(globex);

        mvc.perform(get("/v1/invoices/{id}", globexInvoice).header(HttpHeaders.AUTHORIZATION, admin(acme.tenantId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVOICE_NOT_FOUND"));
        mvc.perform(get("/v1/invoices/{id}", UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, admin(acme.tenantId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVOICE_NOT_FOUND"));
    }

    @Test
    void billingIsAdminOnlyAndRequiresAuthentication() throws Exception {
        Acme acme = BillingFixtures.acme();
        UUID invoiceId = invoiceOf(acme.tenantId());
        String viewer = user(acme.tenantId());

        mvc.perform(get("/v1/invoices").header(HttpHeaders.AUTHORIZATION, viewer)).andExpect(status().isForbidden());
        mvc.perform(get("/v1/invoices/{id}", invoiceId).header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/v1/billing/preview").header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(status().isForbidden());
        mvc.perform(post("/v1/billing/run").header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(status().isForbidden());
        mvc.perform(get("/v1/invoices")).andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/billing/run")).andExpect(status().isUnauthorized());
    }

    @Test
    void previewPricesTheOpenPeriodWithoutPersistingAnything() throws Exception {
        Acme acme = BillingFixtures.acme();

        mvc.perform(get("/v1/billing/preview").header(HttpHeaders.AUTHORIZATION, admin(acme.tenantId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(nullValue()))
                .andExpect(jsonPath("$.invoice_number").value(nullValue()))
                .andExpect(jsonPath("$.issued_at").value(nullValue()))
                .andExpect(jsonPath("$.status").value("PREVIEW"))
                .andExpect(jsonPath("$.period_start").value("2026-09-01"))
                .andExpect(jsonPath("$.period_end").value("2026-10-01"))
                .andExpect(jsonPath("$.plan.code").value("STARTER"))
                // September so far: 7,777 API calls (inside the allowance) and no webhooks.
                .andExpect(jsonPath("$.total_amount").value("19.00"))
                .andExpect(jsonPath("$.line_items[1].description").value("API calls — included (1–7,777)"))
                .andExpect(jsonPath("$.line_items[2].description").value("Webhooks delivered — included (no usage)"));

        assertThat(BillingFixtures.count("SELECT count(*) FROM invoice")).isZero();
        assertThat(BillingFixtures.count("SELECT count(*) FROM usage_period_aggregate")).isZero();
        assertThat(BillingFixtures.count("SELECT count(*) FROM outbox_event")).isZero();
    }

    @Test
    void previewNeedsASubscriptionToo() throws Exception {
        UUID tenantId = BillingFixtures.tenant("No subscription");

        mvc.perform(get("/v1/billing/preview").header(HttpHeaders.AUTHORIZATION, admin(tenantId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_SUBSCRIPTION"));
    }
}
