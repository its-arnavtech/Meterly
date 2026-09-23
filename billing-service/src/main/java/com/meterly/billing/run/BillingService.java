package com.meterly.billing.run;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meterly.billing.BillingErrors;
import com.meterly.billing.domain.InvoiceDraft;
import com.meterly.billing.domain.PricingEngine;
import com.meterly.billing.domain.ResourceUsage;
import com.meterly.billing.invoice.InvoiceDetail;
import com.meterly.billing.invoice.InvoiceRepository;
import com.meterly.billing.invoice.InvoiceRepository.NewInvoice;
import com.meterly.billing.outbox.OutboxRepository;
import com.meterly.billing.outbox.OutboxRepository.OutboxEvent;
import com.meterly.billing.run.BillingMetrics.Outcome;
import com.meterly.billing.run.BillingRunRepository.SubscribedPlan;
import com.meterly.common.billing.BillingPeriod;
import com.meterly.common.event.NotificationEvent;
import com.meterly.common.event.Topics;
import com.meterly.common.tenant.TenantContext;
import com.meterly.common.web.ApiException;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Generates invoices (period close) and invoice previews (open period).
 *
 * <p>{@link #run} executes in <em>one</em> transaction bound to the tenant (RLS): take the advisory lock for
 * tenant+period, return the existing invoice if there is one, otherwise materialize the usage aggregates
 * from the ledger, price them with the plan of the covering subscription, insert the invoice (with its plan
 * snapshot) and line items, and append {@code InvoiceIssued} to the outbox. Either all of it commits or
 * nothing does; a concurrent or repeated run for the same period returns the same invoice.
 */
@Service
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    private final BillingRunRepository runs;
    private final InvoiceRepository invoices;
    private final OutboxRepository outbox;
    private final PricingEngine pricing;
    private final BillingPeriods periods;
    private final BillingMetrics metrics;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;

    public BillingService(BillingRunRepository runs, InvoiceRepository invoices, OutboxRepository outbox,
                          PricingEngine pricing, BillingPeriods periods, BillingMetrics metrics,
                          ObjectMapper objectMapper, Clock clock, PlatformTransactionManager transactionManager) {
        this.runs = runs;
        this.invoices = invoices;
        this.outbox = outbox;
        this.pricing = pricing;
        this.periods = periods;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
    }

    /**
     * Invoices {@code period} for {@code tenantId}.
     *
     * @throws ApiException {@code 422 PERIOD_NOT_CLOSED} if the period has not ended,
     *                      {@code 422 NO_SUBSCRIPTION} if no subscription covers it
     */
    public BillingRunResult run(UUID tenantId, BillingPeriod period) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(period, "period");
        periods.requireClosed(period);
        Timer.Sample sample = metrics.start();
        Outcome outcome = Outcome.FAILED;
        try {
            BillingRunResult result = TenantContext.callAs(tenantId,
                    () -> writeTransaction.execute(status -> generate(tenantId, period)));
            outcome = Outcome.of(result);
            return result;
        } catch (ApiException e) {
            if (BillingErrors.isNoSubscription(e)) {
                outcome = Outcome.SKIPPED_NO_SUBSCRIPTION;
            }
            throw e;
        } finally {
            metrics.record(sample, outcome);
        }
    }

    /** Estimated invoice for the open period from the ledger so far; nothing is written. */
    public InvoiceDetail preview(UUID tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        BillingPeriod period = periods.current();
        return TenantContext.callAs(tenantId, () -> readTransaction.execute(status -> {
            SubscribedPlan plan = runs.findSubscribedPlan(tenantId, period)
                    .orElseThrow(() -> BillingErrors.noSubscription(period));
            InvoiceDraft draft = pricing.price(plan.pricing(), runs.ledgerTotals(tenantId, period));
            return InvoiceDetail.preview(period, draft);
        }));
    }

    private BillingRunResult generate(UUID tenantId, BillingPeriod period) {
        runs.lockTenantPeriod(tenantId, period);
        var existing = invoices.findIdForPeriod(tenantId, period.start());
        if (existing.isPresent()) {
            return new BillingRunResult.AlreadyInvoiced(requireDetail(tenantId, existing.get()));
        }

        SubscribedPlan plan = runs.findSubscribedPlan(tenantId, period)
                .orElseThrow(() -> BillingErrors.noSubscription(period));
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        List<ResourceUsage> usage = runs.ledgerTotals(tenantId, period);
        runs.upsertAggregates(tenantId, period, usage, now);
        InvoiceDraft draft = pricing.price(plan.pricing(), usage);

        UUID invoiceId = UUID.randomUUID();
        var invoiceNumber = invoices.insert(new NewInvoice(invoiceId, tenantId, plan.planId(), period, draft, now));
        if (invoiceNumber.isEmpty()) {
            // Safety net behind the advisory lock: another transaction inserted the invoice first.
            UUID winner = invoices.findIdForPeriod(tenantId, period.start()).orElseThrow();
            return new BillingRunResult.AlreadyInvoiced(requireDetail(tenantId, winner));
        }
        invoices.insertLineItems(tenantId, invoiceId, draft.lineItems());
        outbox.insert(invoiceIssued(tenantId, invoiceId, invoiceNumber.get(), period, draft, now));

        log.info("Issued invoice {} for tenant {} period {}: {} {}", invoiceNumber.get(), tenantId, period,
                draft.totalAmount().toPlainString(), draft.currency());
        return new BillingRunResult.Created(requireDetail(tenantId, invoiceId));
    }

    private InvoiceDetail requireDetail(UUID tenantId, UUID invoiceId) {
        return invoices.findDetail(tenantId, invoiceId)
                .orElseThrow(() -> new IllegalStateException("Invoice " + invoiceId + " vanished inside its transaction"));
    }

    private OutboxEvent invoiceIssued(UUID tenantId, UUID invoiceId, String invoiceNumber, BillingPeriod period,
                                      InvoiceDraft draft, Instant issuedAt) {
        UUID eventId = UUID.randomUUID();
        NotificationEvent event = new NotificationEvent.InvoiceIssued(eventId, tenantId, issuedAt, invoiceId,
                invoiceNumber, period.start(), period.end(), draft.totalAmount(), draft.currency());
        try {
            String payload = objectMapper.writerFor(NotificationEvent.class).writeValueAsString(event);
            return new OutboxEvent(eventId, tenantId, Topics.NOTIFICATION_EVENTS, tenantId.toString(),
                    NotificationEvent.INVOICE_ISSUED, payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize InvoiceIssued event " + eventId, e);
        }
    }
}
