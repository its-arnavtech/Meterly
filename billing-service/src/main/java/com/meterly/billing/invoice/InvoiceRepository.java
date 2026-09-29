package com.meterly.billing.invoice;

import com.meterly.billing.domain.InvoiceDraft;
import com.meterly.billing.domain.LineItem;
import com.meterly.billing.invoice.InvoiceDetail.PlanRef;
import com.meterly.common.billing.BillingPeriod;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL for {@code invoice} and {@code invoice_line_item}. Must run inside a tenant-bound transaction: RLS
 * scopes every statement to the bound tenant, and the explicit {@code tenant_id} predicates are only for
 * index use and readability.
 */
@Repository
public class InvoiceRepository {

    private static final String SELECT_INVOICE = """
            SELECT id, invoice_number, period_start, period_end, status, currency, total_amount, issued_at,
                   plan_code, plan_name
            FROM invoice
            """;

    private final JdbcClient jdbc;

    public InvoiceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Values of a new invoice row; the invoice number is assigned by the database. */
    public record NewInvoice(UUID id, UUID tenantId, UUID planId, BillingPeriod period, InvoiceDraft draft,
                             Instant issuedAt) {
    }

    public Optional<UUID> findIdForPeriod(UUID tenantId, LocalDate periodStart) {
        return jdbc.sql("SELECT id FROM invoice WHERE tenant_id = :tenantId AND period_start = :periodStart")
                .param("tenantId", tenantId)
                .param("periodStart", periodStart)
                .query(UUID.class)
                .optional();
    }

    /**
     * Inserts the invoice header and returns its number, or empty when an invoice for the tenant and
     * period already exists ({@code ON CONFLICT (tenant_id, period_start) DO NOTHING}). The number is
     * {@code INV-<yyyyMM>-<sequence, zero-padded to at least 6 digits>}.
     */
    public Optional<String> insert(NewInvoice invoice) {
        InvoiceDraft draft = invoice.draft();
        return jdbc.sql("""
                        WITH seq AS (SELECT nextval('invoice_number_seq')::text AS n)
                        INSERT INTO invoice (id, tenant_id, invoice_number, plan_id, plan_code, plan_name,
                                             period_start, period_end, status, currency, total_amount, issued_at)
                        SELECT :id, :tenantId,
                               'INV-' || to_char(CAST(:periodStart AS date), 'YYYYMM') || '-'
                                      || lpad(seq.n, greatest(6, length(seq.n)), '0'),
                               :planId, :planCode, :planName, :periodStart, :periodEnd, 'ISSUED', :currency,
                               :totalAmount, :issuedAt
                        FROM seq
                        ON CONFLICT (tenant_id, period_start) DO NOTHING
                        RETURNING invoice_number
                        """)
                .param("id", invoice.id())
                .param("tenantId", invoice.tenantId())
                .param("periodStart", invoice.period().start())
                .param("periodEnd", invoice.period().end())
                .param("planId", invoice.planId())
                .param("planCode", draft.planCode())
                .param("planName", draft.planName())
                .param("currency", draft.currency())
                .param("totalAmount", draft.totalAmount())
                .param("issuedAt", OffsetDateTime.ofInstant(invoice.issuedAt(), ZoneOffset.UTC))
                .query(String.class)
                .optional();
    }

    public void insertLineItems(UUID tenantId, UUID invoiceId, List<LineItem> lineItems) {
        for (LineItem line : lineItems) {
            jdbc.sql("""
                            INSERT INTO invoice_line_item (tenant_id, invoice_id, line_number, resource_id, description,
                                                           quantity, unit_price, amount)
                            VALUES (:tenantId, :invoiceId, :lineNumber, :resourceId, :description, :quantity,
                                    :unitPrice, :amount)
                            """)
                    .param("tenantId", tenantId)
                    .param("invoiceId", invoiceId)
                    .param("lineNumber", line.lineNumber())
                    .param("resourceId", line.resourceId(), Types.OTHER)
                    .param("description", line.description())
                    .param("quantity", line.quantity())
                    .param("unitPrice", line.unitPrice())
                    .param("amount", line.amount())
                    .update();
        }
    }

    public Optional<InvoiceDetail> findDetail(UUID tenantId, UUID invoiceId) {
        List<LineItem> lines = jdbc.sql("""
                        SELECT line_number, description, resource_id, quantity, unit_price, amount
                        FROM invoice_line_item
                        WHERE tenant_id = :tenantId AND invoice_id = :invoiceId
                        ORDER BY line_number
                        """)
                .param("tenantId", tenantId)
                .param("invoiceId", invoiceId)
                .query((rs, row) -> new LineItem(rs.getInt("line_number"), rs.getString("description"),
                        rs.getObject("resource_id", UUID.class), rs.getLong("quantity"),
                        rs.getBigDecimal("unit_price"), rs.getBigDecimal("amount")))
                .list();
        return jdbc.sql(SELECT_INVOICE + "WHERE tenant_id = :tenantId AND id = :invoiceId")
                .param("tenantId", tenantId)
                .param("invoiceId", invoiceId)
                .query((rs, row) -> toDetail(rs, lines))
                .optional();
    }

    /** All invoices of the tenant, newest period first. */
    public List<InvoiceSummary> list(UUID tenantId) {
        return jdbc.sql(SELECT_INVOICE + "WHERE tenant_id = :tenantId ORDER BY period_start DESC")
                .param("tenantId", tenantId)
                .query((rs, row) -> new InvoiceSummary(rs.getObject("id", UUID.class), rs.getString("invoice_number"),
                        rs.getObject("period_start", LocalDate.class), rs.getObject("period_end", LocalDate.class),
                        rs.getString("status"), rs.getString("currency"), rs.getBigDecimal("total_amount"),
                        issuedAt(rs)))
                .list();
    }

    private static InvoiceDetail toDetail(ResultSet rs, List<LineItem> lines) throws SQLException {
        return new InvoiceDetail(rs.getObject("id", UUID.class), rs.getString("invoice_number"),
                rs.getObject("period_start", LocalDate.class), rs.getObject("period_end", LocalDate.class),
                rs.getString("status"), rs.getString("currency"), rs.getBigDecimal("total_amount"), issuedAt(rs),
                new PlanRef(rs.getString("plan_code"), rs.getString("plan_name")), lines);
    }

    private static Instant issuedAt(ResultSet rs) throws SQLException {
        return rs.getObject("issued_at", OffsetDateTime.class).toInstant();
    }
}
