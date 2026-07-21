package com.meterly.billing.invoice;

import com.meterly.billing.tenant.TenantTransactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
public class BillingService {
    private final JdbcTemplate jdbcTemplate;
    private final TieredPriceCalculator calculator;

    public BillingService(JdbcTemplate jdbcTemplate, TieredPriceCalculator calculator) {
        this.jdbcTemplate = jdbcTemplate;
        this.calculator = calculator;
    }

    @TenantTransactional
    public InvoiceView run(UUID tenantId, LocalDate periodStart, LocalDate periodEnd) {
        if (!periodEnd.isAfter(periodStart)) {
            throw new IllegalArgumentException("period_end must be after period_start");
        }

        UUID resourceId = jdbcTemplate.queryForObject(
                "SELECT id FROM metered_resource ORDER BY name LIMIT 1", UUID.class);
        long totalUsage = materializeAggregate(tenantId, resourceId, periodStart, periodEnd);
        PlanPricing pricing = activePricing(periodStart);
        TieredPriceCalculator.BillingAmounts amounts = calculator.calculate(
                pricing.basePrice(), pricing.includedUnits(), pricing.marginalUnitPrice(), totalUsage);

        UUID invoiceId = UUID.randomUUID();
        List<UUID> inserted = jdbcTemplate.query("""
                INSERT INTO invoice
                    (id, tenant_id, period_start, period_end, status, total_amount)
                VALUES (?, ?, ?, ?, 'FINALIZED', ?)
                ON CONFLICT (tenant_id, period_start) DO NOTHING
                RETURNING id
                """,
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                invoiceId, tenantId, Date.valueOf(periodStart), Date.valueOf(periodEnd), amounts.total());

        if (inserted.isEmpty()) {
            return findByPeriod(periodStart);
        }

        insertLineItem(tenantId, invoiceId, "Developer plan base fee", 1, pricing.basePrice(), amounts.baseAmount());
        if (amounts.overageUnits() > 0) {
            insertLineItem(
                    tenantId,
                    invoiceId,
                    "API calls above " + pricing.includedUnits() + " included units",
                    amounts.overageUnits(),
                    amounts.marginalUnitPrice(),
                    amounts.overageAmount()
            );
        }
        return find(invoiceId);
    }

    private long materializeAggregate(
            UUID tenantId,
            UUID resourceId,
            LocalDate periodStart,
            LocalDate periodEnd
    ) {
        Timestamp from = Timestamp.from(periodStart.atStartOfDay().toInstant(ZoneOffset.UTC));
        Timestamp to = Timestamp.from(periodEnd.atStartOfDay().toInstant(ZoneOffset.UTC));
        Long total = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(quantity), 0) FROM usage_event WHERE resource_id = ? AND occurred_at >= ? AND occurred_at < ?",
                Long.class,
                resourceId,
                from,
                to
        );
        long units = total == null ? 0 : total;
        jdbcTemplate.update("""
                INSERT INTO usage_period_aggregate
                    (id, tenant_id, resource_id, period_start, period_end, total_units)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, resource_id, period_start)
                DO UPDATE SET period_end = EXCLUDED.period_end, total_units = EXCLUDED.total_units
                """,
                UUID.randomUUID(), tenantId, resourceId, Date.valueOf(periodStart), Date.valueOf(periodEnd), units);
        return units;
    }

    private PlanPricing activePricing(LocalDate periodStart) {
        return jdbcTemplate.queryForObject("""
                SELECT p.id, p.base_price, p.included_units, pt.unit_price
                FROM subscription s
                JOIN plan p ON p.id = s.plan_id
                JOIN pricing_tier pt ON pt.plan_id = p.id AND pt.from_unit = p.included_units + 1
                WHERE s.status = 'ACTIVE'
                  AND s.start_date <= ?
                  AND (s.end_date IS NULL OR s.end_date >= ?)
                ORDER BY s.start_date DESC
                LIMIT 1
                """,
                (resultSet, rowNumber) -> new PlanPricing(
                        resultSet.getBigDecimal("base_price"),
                        resultSet.getLong("included_units"),
                        resultSet.getBigDecimal("unit_price")
                ),
                Date.valueOf(periodStart),
                Date.valueOf(periodStart)
        );
    }

    private void insertLineItem(
            UUID tenantId,
            UUID invoiceId,
            String description,
            long quantity,
            BigDecimal unitPrice,
            BigDecimal amount
    ) {
        jdbcTemplate.update("""
                INSERT INTO invoice_line_item
                    (id, tenant_id, invoice_id, description, quantity, unit_price, amount)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), tenantId, invoiceId, description, quantity, unitPrice, amount);
    }

    @TenantTransactional
    public InvoiceView find(UUID invoiceId) {
        InvoiceRow invoice = jdbcTemplate.queryForObject("""
                SELECT id, tenant_id, period_start, period_end, status, total_amount
                FROM invoice WHERE id = ?
                """,
                (resultSet, rowNumber) -> new InvoiceRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("tenant_id", UUID.class),
                        resultSet.getObject("period_start", LocalDate.class),
                        resultSet.getObject("period_end", LocalDate.class),
                        resultSet.getString("status"),
                        resultSet.getBigDecimal("total_amount")
                ),
                invoiceId
        );
        List<InvoiceView.LineItemView> lineItems = jdbcTemplate.query("""
                SELECT id, description, quantity, unit_price, amount
                FROM invoice_line_item WHERE invoice_id = ? ORDER BY description
                """,
                (resultSet, rowNumber) -> new InvoiceView.LineItemView(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("description"),
                        resultSet.getLong("quantity"),
                        resultSet.getBigDecimal("unit_price"),
                        resultSet.getBigDecimal("amount")
                ),
                invoiceId
        );
        return new InvoiceView(
                invoice.id(), invoice.tenantId(), invoice.periodStart(), invoice.periodEnd(),
                invoice.status(), invoice.totalAmount(), lineItems);
    }

    @TenantTransactional
    public InvoiceView findByPeriod(LocalDate periodStart) {
        UUID invoiceId = jdbcTemplate.queryForObject(
                "SELECT id FROM invoice WHERE period_start = ?", UUID.class, Date.valueOf(periodStart));
        return find(invoiceId);
    }

    @TenantTransactional
    public List<InvoiceView> list() {
        return jdbcTemplate.queryForList(
                        "SELECT id FROM invoice ORDER BY period_start DESC", UUID.class)
                .stream()
                .map(this::find)
                .toList();
    }

    private record PlanPricing(BigDecimal basePrice, long includedUnits, BigDecimal marginalUnitPrice) {
    }

    private record InvoiceRow(
            UUID id,
            UUID tenantId,
            LocalDate periodStart,
            LocalDate periodEnd,
            String status,
            BigDecimal totalAmount
    ) {
    }
}

