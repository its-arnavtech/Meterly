package com.meterly.validation.checks;

import com.meterly.validation.db.Database;
import com.meterly.validation.report.CheckResult;
import com.meterly.validation.report.ValidationReport;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;

/**
 * Contract §9 item 1: proves PostgreSQL RLS, not an application-layer filter, is what isolates
 * tenants — every query below deliberately omits {@code WHERE tenant_id = ...}. Mirrors
 * {@code meterly-db}'s {@code RowLevelSecurityIT}, but through the harness's own JDBC connection
 * against the live compose stack rather than Testcontainers.
 */
public final class RlsCheck {

    private RlsCheck() {
    }

    public static void run(Database db, ValidationReport report, Organization tenantA, Organization tenantB) {
        try {
            checkBoundTenantSeesOnlyItself(db, report, tenantA, tenantB);
            checkBoundTenantSeesOnlyItself(db, report, tenantB, tenantA);
            checkNoTenantBoundSeesNothing(db, report);
        } catch (SQLException e) {
            report.record(CheckResult.fail("RLS isolation", "JDBC error: " + e.getMessage()));
        }
    }

    private static void checkBoundTenantSeesOnlyItself(Database db, ValidationReport report,
            Organization bound, Organization other) throws SQLException {
        Set<UUID> visible = db.runAsTenant(bound.tenantId(), db::distinctLedgerTenantIds);
        long tenantCount = db.runAsTenant(bound.tenantId(), db::countTenants);

        boolean seesOwn = visible.contains(bound.tenantId());
        boolean seesOther = visible.contains(other.tenantId());
        boolean passed = seesOwn && !seesOther && tenantCount == 1;

        String message = "bound as %s: unfiltered usage_event shows tenant_ids=%s (own=%s, other(%s)=%s); tenant table count=%d"
                .formatted(bound.label(), visible, seesOwn, other.label(), seesOther, tenantCount);
        report.record(passed
                ? CheckResult.pass("RLS: " + bound.label() + " bound sees only itself", message)
                : CheckResult.fail("RLS: " + bound.label() + " bound sees only itself", message));
    }

    private static void checkNoTenantBoundSeesNothing(Database db, ValidationReport report) throws SQLException {
        Set<UUID> visible = db.runAsTenant(null, db::distinctLedgerTenantIds);
        long tenantCount = db.runAsTenant(null, db::countTenants);
        boolean passed = visible.isEmpty() && tenantCount == 0;

        String message = "no tenant bound: unfiltered usage_event tenant_ids=%s, tenant table count=%d"
                .formatted(visible, tenantCount);
        report.record(passed
                ? CheckResult.pass("RLS: no tenant bound sees nothing", message)
                : CheckResult.fail("RLS: no tenant bound sees nothing", message));
    }
}
