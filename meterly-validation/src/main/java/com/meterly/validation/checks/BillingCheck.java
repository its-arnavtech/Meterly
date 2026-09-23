package com.meterly.validation.checks;

import com.meterly.common.billing.BillingPeriod;
import com.meterly.validation.billing.TierCalculator;
import com.meterly.validation.http.ApiClient;
import com.meterly.validation.http.ApiResponse;
import com.meterly.validation.http.HarnessException;
import com.meterly.validation.model.BillingModels.Invoice;
import com.meterly.validation.model.BillingModels.LineItem;
import com.meterly.validation.model.BillingModels.Plan;
import com.meterly.validation.model.BillingModels.SubscriptionResponse;
import com.meterly.validation.model.TenantModels.ResourceInfo;
import com.meterly.validation.model.UsageModels.TimeseriesPoint;
import com.meterly.validation.model.UsageModels.TimeseriesResponse;
import com.meterly.validation.model.UsageModels.UsageCurrentResponse;
import com.meterly.validation.report.CheckResult;
import com.meterly.validation.report.ValidationReport;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Contract §9 item 3: cent-accurate billing, independently recomputed by {@link TierCalculator} from
 * {@code GET /v1/plans} and usage read back from the system (never hand-typed figures), for every
 * organization; re-running {@code POST /v1/billing/run} must return the same invoice.
 */
public final class BillingCheck {

    private record RunRequest(java.time.LocalDate periodStart) {
    }

    private BillingCheck() {
    }

    /** Acme/Globex: their usage is in a closed previous period, so this exercises the real billing run. */
    public static UUID runClosedPeriod(ApiClient api, Organization org, ValidationReport report) {
        BillingPeriod previous = BillingPeriod.current(Clock.systemUTC()).previous();
        var plan = fetchPlan(api, org);
        Map<UUID, Long> usage = new LinkedHashMap<>();
        for (ResourceInfo resource : org.resources()) {
            usage.put(resource.id(), sumTimeseries(api, org, resource.id(), previous));
        }
        BigDecimal expected = TierCalculator.invoiceTotal(plan, usage);

        ApiResponse<Invoice> first = api.post("/v1/billing/run", org.bearer(),
                new RunRequest(previous.start()), Invoice.class);
        ApiResponse<Invoice> rerun = api.post("/v1/billing/run", org.bearer(),
                new RunRequest(previous.start()), Invoice.class);

        boolean created = first.status() == 201 || first.status() == 200;
        boolean idempotent = rerun.status() == 200 && first.body() != null && rerun.body() != null
                && first.body().id().equals(rerun.body().id());
        BigDecimal actual = first.body() == null ? null : first.body().totalAmount();
        boolean centAccurate = actual != null && actual.compareTo(expected) == 0;
        boolean lineItemsSumToTotal = first.body() != null && lineItemsSumTo(first.body());

        boolean passed = created && idempotent && centAccurate && lineItemsSumToTotal;
        var metrics = new CheckResult.Metrics()
                .put("period", previous)
                .put("expected_total", expected)
                .put("actual_total", actual)
                .put("first_run_status", first.status())
                .put("rerun_status", rerun.status())
                .put("invoice_id", first.body() == null ? null : first.body().id())
                .put("line_items_sum_to_total", lineItemsSumToTotal)
                .build();
        String message = passed
                ? "invoice total %s matches independently recomputed %s; re-run returned the same invoice (%s)"
                        .formatted(actual, expected, first.body().id())
                : "expected %s, got %s (created=%s idempotent=%s lineItemsOk=%s, first=%d rerun=%d)"
                        .formatted(expected, actual, created, idempotent, lineItemsSumToTotal, first.status(), rerun.status());
        report.record(passed
                ? CheckResult.pass("Cent-accurate billing: " + org.label(), message, metrics)
                : CheckResult.fail("Cent-accurate billing: " + org.label(), message, metrics));
        return first.body() == null ? null : first.body().id();
    }

    /** The fresh tenant's usage is in the still-open period, so verify the preview instead of a real run. */
    public static void runOpenPeriodPreview(ApiClient api, Organization org, ValidationReport report) {
        var plan = fetchPlan(api, org);
        ApiResponse<UsageCurrentResponse> current = api.get("/v1/usage/current", org.bearer(), UsageCurrentResponse.class);
        Map<UUID, Long> usage = new LinkedHashMap<>();
        if (current.body() != null) {
            current.body().items().forEach(item -> usage.put(item.resourceId(), item.totalUnits()));
        }
        BigDecimal expected = TierCalculator.invoiceTotal(plan, usage);

        ApiResponse<Invoice> preview = api.get("/v1/billing/preview", org.bearer(), Invoice.class);
        BigDecimal actual = preview.body() == null ? null : preview.body().totalAmount();
        boolean previewShapeOk = preview.status() == 200 && preview.body() != null
                && "PREVIEW".equals(preview.body().status()) && preview.body().id() == null;
        boolean centAccurate = actual != null && actual.compareTo(expected) == 0;

        boolean passed = previewShapeOk && centAccurate;
        var metrics = new CheckResult.Metrics()
                .put("expected_total", expected)
                .put("actual_total", actual)
                .put("preview_status", preview.status())
                .build();
        String message = passed
                ? "open-period preview total %s matches independently recomputed %s".formatted(actual, expected)
                : "expected %s, got %s (status=%d, shapeOk=%s)".formatted(expected, actual, preview.status(), previewShapeOk);
        report.record(passed
                ? CheckResult.pass("Cent-accurate billing preview: " + org.label(), message, metrics)
                : CheckResult.fail("Cent-accurate billing preview: " + org.label(), message, metrics));
    }

    public static void crossTenantInvoiceIsInvisible(ApiClient api, Organization viewer, UUID otherInvoiceId, ValidationReport report) {
        if (otherInvoiceId == null) {
            report.record(CheckResult.fail("Cross-tenant invoice isolation", "no invoice id available to test with"));
            return;
        }
        ApiResponse<Invoice> response = api.get("/v1/invoices/" + otherInvoiceId, viewer.bearer(), Invoice.class);
        boolean passed = response.status() == 404;
        report.record(passed
                ? CheckResult.pass("Cross-tenant invoice isolation", viewer.label() + " -> other tenant's invoice: 404 as expected")
                : CheckResult.fail("Cross-tenant invoice isolation",
                        viewer.label() + " -> other tenant's invoice: expected 404, got " + response.status()));
    }

    private static Plan fetchPlan(ApiClient api, Organization org) {
        ApiResponse<SubscriptionResponse> subscription = api.get("/v1/subscription", org.bearer(), SubscriptionResponse.class);
        if (subscription.body() == null || subscription.body().current() == null) {
            throw new HarnessException(
                    "No active subscription for " + org.label() + ": " + subscription.rawBody());
        }
        return subscription.body().current().plan();
    }

    private static long sumTimeseries(ApiClient api, Organization org, UUID resourceId, BillingPeriod period) {
        String from = DateTimeFormatter.ISO_INSTANT.format(period.startInstant());
        String to = DateTimeFormatter.ISO_INSTANT.format(period.endInstant());
        String path = "/v1/usage/timeseries?resource_id=%s&granularity=day&from=%s&to=%s".formatted(
                encode(resourceId.toString()), encode(from), encode(to));
        ApiResponse<TimeseriesResponse> response = api.get(path, org.bearer(), TimeseriesResponse.class);
        if (response.body() == null) {
            throw new HarnessException(
                    "Timeseries fetch failed for " + org.label() + "/" + resourceId + ": " + response.rawBody());
        }
        return response.body().points().stream().mapToLong(TimeseriesPoint::units).sum();
    }

    private static boolean lineItemsSumTo(Invoice invoice) {
        BigDecimal sum = BigDecimal.ZERO;
        for (LineItem item : invoice.lineItems()) {
            sum = sum.add(item.amount());
        }
        return sum.compareTo(invoice.totalAmount()) == 0;
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
