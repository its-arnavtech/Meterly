package com.meterly.validation.checks;

import com.meterly.common.event.UsageUpdate;
import com.meterly.validation.checks.IngestionClient.PostOutcome;
import com.meterly.validation.http.ApiClient;
import com.meterly.validation.report.CheckResult;
import com.meterly.validation.report.Percentiles;
import com.meterly.validation.report.ValidationReport;
import com.meterly.validation.stomp.StompClient;
import com.meterly.validation.stomp.StompFrame;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Contract §9 item 5: at least 50 trials from an ingestion {@code 202} to the matching STOMP
 * {@code MESSAGE} frame, reporting p50/p95/p99/max; passes iff p95 &lt; 850ms and at least 18 of the
 * first 20 trials land within 2s (the POC §7 bar). Runs sequentially against the fresh tenant's
 * dedicated "Stomp Latency Test" resource so no other check's traffic can land in the same queue.
 */
public final class StompLatencyCheck {

    private static final int TRIALS = 55;
    private static final Duration MESSAGE_TIMEOUT = Duration.ofSeconds(5);
    private static final long P95_BAR_MILLIS = 850;
    private static final long POC_BAR_MILLIS = 2000;
    private static final int POC_BAR_TRIALS = 20;
    private static final int POC_BAR_MIN_WITHIN = 18;

    private StompLatencyCheck() {
    }

    public static void run(ApiClient api, String baseUrl, Organization tenant, ValidationReport report) {
        var resource = tenant.resourceNamed("Stomp Latency Test");
        URI wsUri = toWsUri(baseUrl);
        List<Long> latenciesMillis = new ArrayList<>(TRIALS);
        int failures = 0;

        try (StompClient stomp = StompClient.connect(api.rawClient(), wsUri, tenant.adminJwt(), Duration.ofSeconds(10))) {
            stomp.subscribe(UsageUpdate.stompDestination(tenant.tenantId()), "sub-validation");
            for (int i = 0; i < TRIALS; i++) {
                String key = "val-stomp-" + UUID.randomUUID();
                PostOutcome outcome = IngestionClient.postWithBackoff(api, tenant.apiKeyAuth(), resource.id(), key, 1, 10);
                if (!outcome.succeeded()) {
                    failures++;
                    continue;
                }
                long t0 = System.nanoTime(); // as close as practical to "202 received"
                Optional<StompFrame> message = stomp.pollMessage(MESSAGE_TIMEOUT);
                long elapsedMillis = (System.nanoTime() - t0) / 1_000_000;
                if (message.isEmpty()) {
                    failures++;
                    continue;
                }
                latenciesMillis.add(elapsedMillis);
            }
        } catch (RuntimeException e) {
            report.record(CheckResult.fail("STOMP latency", "could not complete trials: " + e.getMessage()));
            return;
        }

        Percentiles.Summary summary = Percentiles.of(latenciesMillis);
        int within2s = Percentiles.countUnder(latenciesMillis, POC_BAR_TRIALS, POC_BAR_MILLIS);
        boolean p95Ok = summary.p95Under(P95_BAR_MILLIS);
        boolean pocBarOk = within2s >= POC_BAR_MIN_WITHIN;
        boolean enoughTrials = summary.count() >= 50;
        boolean passed = p95Ok && pocBarOk && enoughTrials;

        var metrics = new CheckResult.Metrics()
                .put("trials_completed", summary.count())
                .put("trials_failed", failures)
                .put("p50_ms", summary.p50())
                .put("p95_ms", summary.p95())
                .put("p99_ms", summary.p99())
                .put("max_ms", summary.max())
                .put("within_2s_of_first_20", within2s)
                .build();
        String message = "p50=%dms p95=%dms p99=%dms max=%dms over %d trials (%d failed); %d/20 of the first 20 within 2s"
                .formatted(summary.p50(), summary.p95(), summary.p99(), summary.max(), summary.count(), failures, within2s);
        report.record(passed
                ? CheckResult.pass("STOMP latency (ingestion 202 -> dashboard MESSAGE)", message, metrics)
                : CheckResult.fail("STOMP latency (ingestion 202 -> dashboard MESSAGE)", message, metrics));
    }

    private static URI toWsUri(String baseUrl) {
        URI http = URI.create(baseUrl);
        String scheme = "https".equalsIgnoreCase(http.getScheme()) ? "wss" : "ws";
        return URI.create(scheme + "://" + http.getAuthority() + "/ws/usage");
    }
}
