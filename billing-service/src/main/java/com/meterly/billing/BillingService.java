package com.meterly.billing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meterly.common.billing.BillingPeriod;
import com.meterly.common.event.NotificationEvent;
import com.meterly.common.event.Topics;
import com.meterly.common.tenant.TenantContext;
import com.meterly.common.web.ApiException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BillingService {
    public record PlanSummary(String code, String name) {}
    public record Invoice(UUID id, String invoiceNumber, LocalDate periodStart, LocalDate periodEnd,
                          String status, String currency, BigDecimal totalAmount, Instant issuedAt,
                          PlanSummary plan, List<PricingEngine.Line> lineItems) {}
    public record Result(boolean created, Invoice invoice) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;
    private final PricingEngine pricing = new PricingEngine();

    public BillingService(JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc; this.json = json; this.clock = clock;
    }

    public BillingPeriod closedPeriod(LocalDate start) {
        BillingPeriod period;
        try { period = start == null ? BillingPeriod.current(clock).previous() : BillingPeriod.startingOn(start); }
        catch (IllegalArgumentException e) { throw ApiException.badRequest("INVALID_PERIOD", "Period must start on the first day of a month"); }
        if (!period.isClosedAt(clock.instant()))
            throw ApiException.unprocessable("PERIOD_NOT_CLOSED", "Only closed calendar months can be invoiced");
        return period;
    }

    @Transactional
    public Result run(LocalDate start) {
        UUID tenant = TenantContext.require();
        BillingPeriod period = closedPeriod(start);
        // Collision-safe correctness: hash collisions only serialize unrelated runs.
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {}, tenant + ":" + period.start());
        List<UUID> existing = jdbc.query("SELECT id FROM invoice WHERE period_start = ?", (rs, n) -> rs.getObject(1, UUID.class), period.start());
        if (!existing.isEmpty()) return new Result(false, detail(existing.getFirst()));
        PricingEngine.Plan plan = plan(period);
        List<PricingEngine.ResourceUsage> usage = usage(period);
        for (var resource : usage) jdbc.update("""
                INSERT INTO usage_period_aggregate (tenant_id, resource_id, period_start, period_end, total_units)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT (tenant_id, resource_id, period_start)
                DO UPDATE SET total_units = EXCLUDED.total_units, period_end = EXCLUDED.period_end, computed_at = now()
                """, tenant, resource.id(), period.start(), period.end(), resource.units());
        PricingEngine.Quote quote = pricing.price(plan, usage);
        UUID id = UUID.randomUUID();
        long sequence = jdbc.queryForObject("SELECT nextval('invoice_number_seq')", Long.class);
        String number = "INV-" + period.start().format(DateTimeFormatter.ofPattern("yyyyMM")) + "-" + String.format(java.util.Locale.ROOT, "%06d", sequence);
        Instant issued = clock.instant();
        int inserted = jdbc.update("""
                INSERT INTO invoice (id, tenant_id, invoice_number, plan_id, period_start, period_end, currency, total_amount, issued_at, plan_code, plan_name)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (tenant_id, period_start) DO NOTHING
                """, id, tenant, number, plan.id(), period.start(), period.end(), plan.currency(), quote.total(), Timestamp.from(issued), plan.code(), plan.name());
        if (inserted == 0) {
            UUID prior = jdbc.queryForObject("SELECT id FROM invoice WHERE period_start = ?", UUID.class, period.start());
            return new Result(false, detail(prior));
        }
        for (var line : quote.lines()) jdbc.update("""
                INSERT INTO invoice_line_item (tenant_id, invoice_id, line_number, resource_id, description, quantity, unit_price, amount)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, tenant, id, line.lineNumber(), line.resourceId(), line.description(), line.quantity(), line.unitPrice(), line.amount());
        UUID eventId = UUID.randomUUID();
        var event = new NotificationEvent.InvoiceIssued(eventId, tenant, issued, id, number, period.start(), period.end(), quote.total(), plan.currency());
        try {
            jdbc.update("INSERT INTO outbox_event (id, tenant_id, topic, event_key, event_type, payload) VALUES (?, ?, ?, ?, ?, ?::jsonb)",
                    eventId, tenant, Topics.NOTIFICATION_EVENTS, tenant.toString(), NotificationEvent.INVOICE_ISSUED,
                    json.writerFor(NotificationEvent.class).writeValueAsString(event));
        } catch (JsonProcessingException e) { throw new IllegalStateException("Cannot serialize invoice event", e); }
        return new Result(true, new Invoice(id, number, period.start(), period.end(), "ISSUED", plan.currency(), quote.total(), issued,
                new PlanSummary(plan.code(), plan.name()), quote.lines()));
    }

    @Transactional(readOnly = true)
    public Invoice preview() {
        TenantContext.require();
        BillingPeriod period = BillingPeriod.current(clock);
        var plan = plan(period);
        var quote = pricing.price(plan, usage(period));
        return new Invoice(null, null, period.start(), period.end(), "PREVIEW", plan.currency(), quote.total(), null,
                new PlanSummary(plan.code(), plan.name()), quote.lines());
    }

    @Transactional(readOnly = true)
    public List<Invoice> list() {
        TenantContext.require();
        return jdbc.query("SELECT * FROM invoice ORDER BY period_start DESC", (rs, n) -> invoice(rs, null));
    }

    @Transactional(readOnly = true)
    public Invoice detail(UUID id) {
        TenantContext.require();
        var lines = jdbc.query("SELECT * FROM invoice_line_item WHERE invoice_id = ? ORDER BY line_number", (rs, n) ->
                new PricingEngine.Line(rs.getInt("line_number"), rs.getString("description"), rs.getObject("resource_id", UUID.class),
                        rs.getLong("quantity"), rs.getBigDecimal("unit_price"), rs.getBigDecimal("amount")), id);
        return jdbc.query("SELECT * FROM invoice WHERE id = ?", (rs, n) -> invoice(rs, lines), id).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("INVOICE_NOT_FOUND", "Invoice not found"));
    }

    private Invoice invoice(ResultSet rs, List<PricingEngine.Line> lines) throws SQLException {
        return new Invoice(rs.getObject("id", UUID.class), rs.getString("invoice_number"), rs.getObject("period_start", LocalDate.class),
                rs.getObject("period_end", LocalDate.class), rs.getString("status"), rs.getString("currency"), rs.getBigDecimal("total_amount"),
                rs.getTimestamp("issued_at").toInstant(), new PlanSummary(rs.getString("plan_code"), rs.getString("plan_name")), lines);
    }

    private PricingEngine.Plan plan(BillingPeriod period) {
        var plans = jdbc.query("""
                SELECT p.* FROM subscription s JOIN plan p ON p.id = s.plan_id
                WHERE s.status = 'ACTIVE' AND s.start_date < ? AND (s.end_date IS NULL OR s.end_date > ?)
                ORDER BY s.start_date LIMIT 2
                """, (rs, n) -> new PricingEngine.Plan(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getBigDecimal("base_price"), rs.getLong("included_units"), rs.getString("currency"), List.of()), period.end(), period.start());
        if (plans.isEmpty()) throw ApiException.unprocessable("NO_SUBSCRIPTION", "No subscription covers this billing period");
        if (plans.size() != 1) throw new IllegalStateException("Multiple plans overlap the billing period");
        var p = plans.getFirst();
        var tiers = jdbc.query("SELECT * FROM pricing_tier WHERE plan_id = ? ORDER BY from_unit", (rs, n) ->
                new PricingEngine.Tier(rs.getLong("from_unit"), rs.getObject("to_unit", Long.class), rs.getBigDecimal("unit_price")), p.id());
        return new PricingEngine.Plan(p.id(), p.code(), p.name(), p.basePrice(), p.includedUnits(), p.currency(), tiers);
    }

    private List<PricingEngine.ResourceUsage> usage(BillingPeriod period) {
        return jdbc.query("""
                SELECT r.id, r.name, COALESCE(SUM(e.quantity), 0)::bigint units
                FROM metered_resource r LEFT JOIN usage_event e ON e.tenant_id = r.tenant_id AND e.resource_id = r.id
                    AND e.occurred_at >= ? AND e.occurred_at < ?
                WHERE r.status = 'ACTIVE' OR e.id IS NOT NULL GROUP BY r.id, r.name ORDER BY r.name, r.id
                """, (rs, n) -> new PricingEngine.ResourceUsage(rs.getObject("id", UUID.class), rs.getString("name"), rs.getLong("units")),
                Timestamp.from(period.startInstant()), Timestamp.from(period.endInstant()));
    }
}
