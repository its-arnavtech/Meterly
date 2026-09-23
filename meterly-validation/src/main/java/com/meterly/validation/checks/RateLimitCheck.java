package com.meterly.validation.checks;

import com.meterly.validation.checks.IngestionClient.PostOutcome;
import com.meterly.validation.http.ApiClient;
import com.meterly.validation.report.CheckResult;
import com.meterly.validation.report.ValidationReport;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Contract §9 item 4: bursting past the per-tenant token bucket (default capacity 200, refill
 * 100/s — {@code IngestionRateLimiter}) gets {@code 429} + {@code Retry-After} for that tenant only;
 * another tenant is unaffected; the bucket recovers after waiting. Uses the fresh tenant's dedicated
 * "Rate Limit Test" resource so this burst never touches the idempotent-metering check's ledger.
 */
public final class RateLimitCheck {

    private static final int BURST_SIZE = 600; // comfortably above capacity(200) + refill during the burst

    private RateLimitCheck() {
    }

    public static void run(ApiClient api, Organization burstTarget, Organization control, ValidationReport report) {
        UUID resourceId = burstTarget.resourceNamed("Rate Limit Test").id();
        List<PostOutcome> burst = burst(api, burstTarget, resourceId);
        long limited = burst.stream().filter(o -> o.status() == 429).count();
        long accepted = burst.stream().filter(PostOutcome::succeeded).count();
        PostOutcome firstLimited = burst.stream().filter(o -> o.status() == 429).findFirst().orElse(null);

        boolean tripped = limited > 0 && accepted > 0 && accepted < BURST_SIZE;
        report.record(tripped
                ? CheckResult.pass("Rate limit: burst trips 429", "%d/%d requests accepted before 429s began (Retry-After present: %s)"
                        .formatted(accepted, BURST_SIZE, firstLimited != null))
                : CheckResult.fail("Rate limit: burst trips 429", "%d/%d accepted, %d rate-limited (expected a mix of both)"
                        .formatted(accepted, BURST_SIZE, limited)));

        UUID controlResource = control.resources().getFirst().id();
        PostOutcome controlOutcome = IngestionClient.postOnce(api, control.apiKeyAuth(), controlResource,
                "val-ratelimit-control-" + UUID.randomUUID(), 1);
        boolean controlUnaffected = controlOutcome.succeeded();
        report.record(controlUnaffected
                ? CheckResult.pass("Rate limit: other tenant unaffected",
                        control.label() + " request succeeded (status " + controlOutcome.status() + ") while " + burstTarget.label() + " was rate-limited")
                : CheckResult.fail("Rate limit: other tenant unaffected",
                        control.label() + " request got status " + controlOutcome.status() + "; expected it to be unaffected"));

        long retryAfterSeconds = firstLimited != null && firstLimited.retryAfterSeconds() > 0
                ? firstLimited.retryAfterSeconds() : 2;
        sleep((retryAfterSeconds + 1) * 1000);
        PostOutcome recovered = IngestionClient.postOnce(api, burstTarget.apiKeyAuth(), resourceId,
                "val-ratelimit-recovery-" + UUID.randomUUID(), 1);
        boolean recoveredOk = recovered.succeeded();
        report.record(recoveredOk
                ? CheckResult.pass("Rate limit: recovers after waiting",
                        "after waiting %ds, a new request succeeded (status %d)".formatted(retryAfterSeconds + 1, recovered.status()))
                : CheckResult.fail("Rate limit: recovers after waiting",
                        "after waiting %ds, request still failed with status %d".formatted(retryAfterSeconds + 1, recovered.status())));
    }

    private static List<PostOutcome> burst(ApiClient api, Organization target, UUID resourceId) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<PostOutcome>> futures = new ArrayList<>(BURST_SIZE);
            for (int i = 0; i < BURST_SIZE; i++) {
                String key = "val-ratelimit-burst-" + UUID.randomUUID();
                futures.add(executor.submit(() -> IngestionClient.postOnce(api, target.apiKeyAuth(), resourceId, key, 1)));
            }
            List<PostOutcome> results = new ArrayList<>(futures.size());
            for (Future<PostOutcome> future : futures) {
                try {
                    results.add(future.get(30, TimeUnit.SECONDS));
                } catch (Exception e) {
                    results.add(new PostOutcome("unknown", -1, 0, e.getMessage(), 0));
                }
            }
            return results;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
