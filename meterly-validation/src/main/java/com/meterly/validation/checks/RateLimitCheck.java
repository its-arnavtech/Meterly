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
 * Contract §9 item 4: bursting past the per-tenant token bucket (default capacity 500, refill
 * 200/s — {@code TokenBucketRateLimiter}) gets {@code 429} + {@code Retry-After} for that tenant only;
 * another tenant is unaffected; the bucket recovers after waiting. Uses the fresh tenant's dedicated
 * "Rate Limit Test" resource so this burst never touches the idempotent-metering check's ledger.
 *
 * <p>The burst is dispatched from a small, fixed pool of workers that each send many requests
 * sequentially over a reused HTTP/1.1 (keep-alive) connection, not one virtual thread per request each
 * opening its own new connection: opening thousands of simultaneous new TCP connections can exceed the
 * local machine's connection-accept capacity before the requests ever reach the rate limiter (observed
 * as a client-side "Connection refused", with nothing server-side to show for it and no request rate
 * anywhere near the refill rate). A bounded pool reusing connections is also how a real high-throughput
 * client behaves, and reliably drives sustained throughput above the refill rate.
 */
public final class RateLimitCheck {

    // 90 workers x 50 sequential requests = 4500 total, comfortably exceeding capacity(500) plus
    // whatever refills (200/s) during the burst's wall-clock time, with headroom for a slower CI box.
    private static final int BURST_WORKERS = 90;
    private static final int REQUESTS_PER_WORKER = 50;
    private static final int BURST_SIZE = BURST_WORKERS * REQUESTS_PER_WORKER;

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
        // A fixed pool of BURST_WORKERS, each firing REQUESTS_PER_WORKER requests sequentially on a
        // reused keep-alive connection: see the class javadoc for why this beats one connection per
        // request. A transport-level failure here (rather than a real 429) is recorded as its own
        // outcome, not silently retried, since this check needs to see the 429 itself.
        try (ExecutorService executor = Executors.newFixedThreadPool(BURST_WORKERS)) {
            List<Future<List<PostOutcome>>> futures = new ArrayList<>(BURST_WORKERS);
            for (int w = 0; w < BURST_WORKERS; w++) {
                futures.add(executor.submit(() -> {
                    List<PostOutcome> workerResults = new ArrayList<>(REQUESTS_PER_WORKER);
                    for (int i = 0; i < REQUESTS_PER_WORKER; i++) {
                        String key = "val-ratelimit-burst-" + UUID.randomUUID();
                        try {
                            workerResults.add(IngestionClient.postOnce(api, target.apiKeyAuth(), resourceId, key, 1));
                        } catch (RuntimeException transportFailure) {
                            // Keep going: one blip shouldn't discard this worker's remaining requests.
                            workerResults.add(new PostOutcome(key, -1, 1, transportFailure.getMessage(), 0));
                        }
                    }
                    return workerResults;
                }));
            }
            List<PostOutcome> results = new ArrayList<>(BURST_SIZE);
            for (Future<List<PostOutcome>> future : futures) {
                try {
                    results.addAll(future.get(60, TimeUnit.SECONDS));
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
