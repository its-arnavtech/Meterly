package com.meterly.billing.invoice;

import com.meterly.platform.tenant.TenantTransactional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class BillingService {
    private final JdbcTemplate jdbcTemplate;
    private final TieredPriceCalculator calculator;
    public BillingService(JdbcTemplate jdbcTemplate, TieredPriceCalculator calculator) { this.jdbcTemplate = jdbcTemplate; this.calculator = calculator; }

    @TenantTransactional
    public InvoiceView run(UUID tenantId, LocalDate periodStart, LocalDate periodEnd) {
        if (!periodEnd.isAfter(periodStart)) throw new IllegalArgumentException("period_end must be after period_start");
        UUID resourceId = jdbcTemplate.queryForObject("SELECT id FROM metered_resource ORDER BY name LIMIT 1", UUID.class);
        long totalUsage = materializeAggregate(tenantId, resourceId, periodStart, periodEnd);
        PlanPricing pricing = activePricing(periodStart, periodEnd);
        TieredPriceCalculator.BillingAmounts amounts = calculator.calculate(pricing.basePrice(), pricing.includedUnits(), pricing.tiers(), totalUsage);
        UUID invoiceId = UUID.randomUUID();
        List<UUID> inserted = jdbcTemplate.query("""
                INSERT INTO invoice (id, tenant_id, period_start, period_end, status, total_amount)
                VALUES (?, ?, ?, ?, 'FINALIZED', ?) ON CONFLICT (tenant_id, period_start) DO NOTHING RETURNING id
                """, (rs, row) -> rs.getObject("id", UUID.class), invoiceId, tenantId,
                Date.valueOf(periodStart), Date.valueOf(periodEnd), amounts.total());
        if (inserted.isEmpty()) return findByPeriod(periodStart);
        insertLineItem(tenantId, invoiceId, pricing.planName() + " plan base fee", 1, pricing.basePrice(), amounts.baseAmount());
        for (TieredPriceCalculator.TierCharge charge : amounts.tierCharges()) {
            String upper = charge.toUnit() == null ? "unlimited" : charge.toUnit().toString();
            insertLineItem(tenantId, invoiceId, "Units " + charge.fromUnit() + "-" + upper,
                    charge.quantity(), charge.unitPrice(), charge.amount());
        }
        return find(invoiceId);
    }

    private long materializeAggregate(UUID tenantId, UUID resourceId, LocalDate periodStart, LocalDate periodEnd) {
        Timestamp from = Timestamp.from(periodStart.atStartOfDay().toInstant(ZoneOffset.UTC));
        Timestamp to = Timestamp.from(periodEnd.atStartOfDay().toInstant(ZoneOffset.UTC));
        Long total = jdbcTemplate.queryForObject("SELECT COALESCE(SUM(quantity), 0) FROM usage_event WHERE resource_id = ? AND occurred_at >= ? AND occurred_at < ?", Long.class, resourceId, from, to);
        long units = total == null ? 0 : total;
        jdbcTemplate.update("""
                INSERT INTO usage_period_aggregate (id, tenant_id, resource_id, period_start, period_end, total_units)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (tenant_id, resource_id, period_start)
                DO UPDATE SET period_end = EXCLUDED.period_end, total_units = EXCLUDED.total_units
                """, UUID.randomUUID(), tenantId, resourceId, Date.valueOf(periodStart), Date.valueOf(periodEnd), units);
        return units;
    }

    private PlanPricing activePricing(LocalDate periodStart, LocalDate periodEnd) {
        PlanRow plan = jdbcTemplate.queryForObject("""
                SELECT p.id, p.name, p.base_price, p.included_units FROM subscription s
                JOIN plan p ON p.id = s.plan_id WHERE s.status = 'ACTIVE' AND s.start_date < ?
                AND (s.end_date IS NULL OR s.end_date >= ?) ORDER BY s.start_date DESC LIMIT 1
                """, (rs, row) -> new PlanRow(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getBigDecimal("base_price"), rs.getLong("included_units")), Date.valueOf(periodEnd), Date.valueOf(periodStart));
        List<TieredPriceCalculator.PricingTier> tiers = jdbcTemplate.query("""
                SELECT from_unit, to_unit, unit_price FROM pricing_tier WHERE plan_id = ? ORDER BY from_unit
                """, (rs, row) -> new TieredPriceCalculator.PricingTier(rs.getLong("from_unit"),
                rs.getObject("to_unit", Long.class), rs.getBigDecimal("unit_price")), plan.id());
        return new PlanPricing(plan.name(), plan.basePrice(), plan.includedUnits(), tiers);
    }

    private void insertLineItem(UUID tenantId, UUID invoiceId, String description, long quantity, BigDecimal unitPrice, BigDecimal amount) {
        jdbcTemplate.update("INSERT INTO invoice_line_item (id, tenant_id, invoice_id, description, quantity, unit_price, amount) VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), tenantId, invoiceId, description, quantity, unitPrice, amount);
    }

    @TenantTransactional
    public InvoiceView find(UUID invoiceId) {
        try {
            InvoiceRow invoice = jdbcTemplate.queryForObject("SELECT id, tenant_id, period_start, period_end, status, total_amount FROM invoice WHERE id = ?",
                    (rs, row) -> new InvoiceRow(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                            rs.getObject("period_start", LocalDate.class), rs.getObject("period_end", LocalDate.class),
                            rs.getString("status"), rs.getBigDecimal("total_amount")), invoiceId);
            List<InvoiceView.LineItemView> lines = jdbcTemplate.query("SELECT id, description, quantity, unit_price, amount FROM invoice_line_item WHERE invoice_id = ? ORDER BY description",
                    (rs, row) -> new InvoiceView.LineItemView(rs.getObject("id", UUID.class), rs.getString("description"),
                            rs.getLong("quantity"), rs.getBigDecimal("unit_price"), rs.getBigDecimal("amount")), invoiceId);
            return new InvoiceView(invoice.id(), invoice.tenantId(), invoice.periodStart(), invoice.periodEnd(), invoice.status(), invoice.totalAmount(), lines);
        } catch (EmptyResultDataAccessException exception) {
            throw new ResponseStatusException(NOT_FOUND, "Invoice not found", exception);
        }
    }

    @TenantTransactional
    public InvoiceView findByPeriod(LocalDate periodStart) {
        try { return find(jdbcTemplate.queryForObject("SELECT id FROM invoice WHERE period_start = ?", UUID.class, Date.valueOf(periodStart))); }
        catch (EmptyResultDataAccessException exception) { throw new ResponseStatusException(NOT_FOUND, "Invoice not found", exception); }
    }

    @TenantTransactional
    public List<InvoiceView> list() {
        return jdbcTemplate.queryForList("SELECT id FROM invoice ORDER BY period_start DESC", UUID.class).stream().map(this::find).toList();
    }

    private record PlanRow(UUID id, String name, BigDecimal basePrice, long includedUnits) { }
    private record PlanPricing(String planName, BigDecimal basePrice, long includedUnits, List<TieredPriceCalculator.PricingTier> tiers) { }
    private record InvoiceRow(UUID id, UUID tenantId, LocalDate periodStart, LocalDate periodEnd, String status, BigDecimal totalAmount) { }
}
