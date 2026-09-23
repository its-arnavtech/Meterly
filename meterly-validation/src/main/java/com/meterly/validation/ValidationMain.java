package com.meterly.validation;

import com.meterly.validation.checks.BillingCheck;
import com.meterly.validation.checks.MeteringIdempotencyCheck;
import com.meterly.validation.checks.Organization;
import com.meterly.validation.checks.RateLimitCheck;
import com.meterly.validation.checks.RlsCheck;
import com.meterly.validation.checks.StompLatencyCheck;
import com.meterly.validation.checks.TenantsSetup;
import com.meterly.validation.cli.HarnessArgs;
import com.meterly.validation.db.Database;
import com.meterly.validation.http.ApiClient;
import com.meterly.validation.load.LoadCommand;
import com.meterly.validation.report.CheckResult;
import com.meterly.validation.report.ValidationReport;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Entry point for the black-box exit-criteria harness (docs/api-contract.md §9, docs/Meterly_POC.txt
 * §7). Runs entirely against a live Docker Compose stack over HTTP, JDBC and the Kafka client - no
 * Testcontainers, no mocks. Exits 0 only if every check passed.
 *
 * <pre>
 *   java -jar meterly-validation.jar [validate] --base-url http://localhost:8080 \
 *       --jdbc-url jdbc:postgresql://localhost:55432/meterly --kafka localhost:19092 \
 *       --report validation-output/validation-report.md
 *   java -jar meterly-validation.jar load --api-key mk_live_... --resource-id &lt;uuid&gt;
 * </pre>
 *
 * {@code DB_APP_PASSWORD} must be set in the environment; it is never accepted as a CLI flag and
 * never printed.
 */
public final class ValidationMain {

    private ValidationMain() {
    }

    public static void main(String[] args) {
        HarnessArgs parsed = HarnessArgs.parse(args);
        if ("load".equals(parsed.command())) {
            System.exit(LoadCommand.run(parsed));
            return;
        }
        System.exit(runValidation(parsed));
    }

    private static int runValidation(HarnessArgs args) {
        String baseUrl = args.get("base-url", "http://localhost:8080");
        String jdbcUrl = args.get("jdbc-url", "jdbc:postgresql://localhost:55432/meterly");
        String dbUser = args.get("db-user", "meterly_app");
        String kafkaBootstrap = args.get("kafka", "localhost:19092");
        Path reportPath = Path.of(args.get("report", "validation-output/validation-report.md"));
        String dbPassword = System.getenv("DB_APP_PASSWORD");

        ValidationReport report = new ValidationReport(System.out);
        if (dbPassword == null || dbPassword.isBlank()) {
            report.record(CheckResult.fail("Setup", "DB_APP_PASSWORD is not set in the environment"));
            return finish(report, reportPath);
        }

        ApiClient api = new ApiClient(baseUrl);
        Organization acme;
        Organization globex;
        Organization fresh;
        try {
            TenantsSetup setup = new TenantsSetup(api);
            acme = setup.loginSeeded("Acme", "admin@acme.test", "Meterly!2026");
            globex = setup.loginSeeded("Globex", "admin@globex.test", "Meterly!2026");
            fresh = setup.signupFresh("Fresh Tenant");
        } catch (RuntimeException e) {
            report.record(CheckResult.fail("Setup", "Could not bring up the three organizations: " + e.getMessage()));
            return finish(report, reportPath);
        }
        report.record(CheckResult.pass("Three organizations active",
                "%s (%s, seeded), %s (%s, seeded), %s (%s, created via POST /v1/tenants)"
                        .formatted(acme.label(), acme.tenantId(), globex.label(), globex.tenantId(), fresh.label(), fresh.tenantId())));

        try (Database db = Database.connect(jdbcUrl, dbUser, dbPassword)) {
            RlsCheck.run(db, report, acme, globex);
            MeteringIdempotencyCheck.run(api, db, kafkaBootstrap, fresh, report);
        } catch (Exception e) {
            report.record(CheckResult.fail("Database checks", "JDBC error: " + e.getMessage()));
        }

        try {
            UUID acmeInvoiceId = BillingCheck.runClosedPeriod(api, acme, report);
            UUID globexInvoiceId = BillingCheck.runClosedPeriod(api, globex, report);
            BillingCheck.runOpenPeriodPreview(api, fresh, report);
            BillingCheck.crossTenantInvoiceIsInvisible(api, acme, globexInvoiceId, report);
            BillingCheck.crossTenantInvoiceIsInvisible(api, globex, acmeInvoiceId, report);
        } catch (RuntimeException e) {
            report.record(CheckResult.fail("Billing checks", "unexpected error: " + e.getMessage()));
        }

        try {
            RateLimitCheck.run(api, fresh, acme, report);
        } catch (RuntimeException e) {
            report.record(CheckResult.fail("Rate limit checks", "unexpected error: " + e.getMessage()));
        }

        try {
            StompLatencyCheck.run(api, baseUrl, fresh, report);
        } catch (RuntimeException e) {
            report.record(CheckResult.fail("STOMP latency", "unexpected error: " + e.getMessage()));
        }

        return finish(report, reportPath);
    }

    private static int finish(ValidationReport report, Path reportPath) {
        report.printSummary();
        try {
            report.writeMarkdown(reportPath);
        } catch (Exception e) {
            System.err.println("Failed to write report to " + reportPath + ": " + e.getMessage());
        }
        return report.allPassed() ? 0 : 1;
    }
}
