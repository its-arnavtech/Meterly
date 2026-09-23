package com.meterly.validation.checks;

import com.meterly.common.event.UsageEventMessage;
import com.meterly.validation.checks.IngestionClient.PostOutcome;
import com.meterly.validation.db.Database;
import com.meterly.validation.db.Database.CountSum;
import com.meterly.validation.http.ApiClient;
import com.meterly.validation.http.Auth;
import com.meterly.validation.http.HarnessException;
import com.meterly.validation.kafka.RedeliverySimulator;
import com.meterly.validation.model.UsageModels.UsageCurrentResponse;
import com.meterly.validation.model.UsageModels.UsageItem;
import com.meterly.validation.report.CheckResult;
import com.meterly.validation.report.ValidationReport;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Contract §9 item 2: 950 unique events, 50 client-side duplicate retries and 50 Kafka redeliveries
 * must settle on exactly 950 ledger rows / 950 total units. Runs against the fresh tenant's own
 * "API calls" resource (a clean ledger, not shared with the demo seed's historical usage) so the
 * final count is an unambiguous assertion.
 */
public final class MeteringIdempotencyCheck {

    private static final int UNIQUE_EVENTS = 950;
    private static final int DUPLICATE_RETRIES = 50;
    private static final int KAFKA_REDELIVERIES = 50;
    private static final int MAX_ATTEMPTS_PER_EVENT = 40; // generous: bucket refills at 100/s

    private MeteringIdempotencyCheck() {
    }

    public static void run(ApiClient api, Database db, String kafkaBootstrap, Organization tenant, ValidationReport report) {
        var resource = tenant.resourceNamed("API calls");
        Auth auth = tenant.apiKeyAuth();
        List<String> keys = new ArrayList<>(UNIQUE_EVENTS);
        for (int i = 0; i < UNIQUE_EVENTS; i++) {
            keys.add("val-metering-" + UUID.randomUUID());
        }

        long httpStart = System.nanoTime();
        List<PostOutcome> outcomes = sendConcurrently(api, auth, resource.id(), keys);
        long httpAcceptedMillis = (System.nanoTime() - httpStart) / 1_000_000;
        long accepted = outcomes.stream().filter(PostOutcome::succeeded).count();

        List<PostOutcome> duplicateRetries = new ArrayList<>(DUPLICATE_RETRIES);
        for (int i = 0; i < DUPLICATE_RETRIES; i++) {
            duplicateRetries.add(IngestionClient.postWithBackoff(api, auth, resource.id(), keys.get(i), 1, MAX_ATTEMPTS_PER_EVENT));
        }
        long confirmedDuplicates = duplicateRetries.stream()
                .filter(o -> o.succeeded() && o.rawBody() != null && o.rawBody().contains("\"status\":\"duplicate\""))
                .count();

        redeliverViaKafka(kafkaBootstrap, tenant.tenantId(), resource.id(), keys.subList(50, 50 + KAFKA_REDELIVERIES));

        StabilizedCount stabilized = pollUntilStable(db, tenant.tenantId(), resource.id());

        UsageCurrentResponse usage = api.get("/v1/usage/current", tenant.bearer(), UsageCurrentResponse.class).body();
        long apiCounter = usage == null ? -1 : usage.items().stream()
                .filter(item -> item.resourceId().equals(resource.id()))
                .mapToLong(UsageItem::totalUnits)
                .findFirst().orElse(-1);

        boolean passed = accepted == UNIQUE_EVENTS && confirmedDuplicates == DUPLICATE_RETRIES
                && stabilized.countSum().count() == UNIQUE_EVENTS && stabilized.countSum().sum() == UNIQUE_EVENTS
                && apiCounter == UNIQUE_EVENTS;

        var metrics = new CheckResult.Metrics()
                .put("http_events_sent", UNIQUE_EVENTS)
                .put("http_events_accepted", accepted)
                .put("http_accept_wall_time_ms", httpAcceptedMillis)
                .put("duplicate_client_retries_sent", DUPLICATE_RETRIES)
                .put("duplicate_client_retries_confirmed_duplicate", confirmedDuplicates)
                .put("kafka_redeliveries_sent", KAFKA_REDELIVERIES)
                .put("ledger_row_count", stabilized.countSum().count())
                .put("ledger_quantity_sum", stabilized.countSum().sum())
                .put("stabilize_poll_ms", stabilized.elapsedMillis())
                .put("api_usage_current_total_units", apiCounter)
                .build();

        String message = passed
                ? "950 unique + 50 duplicate retries + 50 Kafka redeliveries settled at exactly 950 rows / 950 units"
                : "expected 950 rows/units; got ledger count=%d sum=%d, API counter=%d, HTTP accepted=%d/%d, confirmed duplicates=%d/%d"
                        .formatted(stabilized.countSum().count(), stabilized.countSum().sum(), apiCounter,
                                accepted, UNIQUE_EVENTS, confirmedDuplicates, DUPLICATE_RETRIES);
        report.record(passed
                ? CheckResult.pass("Idempotent metering (950 events)", message, metrics)
                : CheckResult.fail("Idempotent metering (950 events)", message, metrics));
    }

    private static List<PostOutcome> sendConcurrently(ApiClient api, Auth auth, UUID resourceId, List<String> keys) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<PostOutcome>> futures = keys.stream()
                    .map(key -> executor.submit(() ->
                            IngestionClient.postWithBackoff(api, auth, resourceId, key, 1, MAX_ATTEMPTS_PER_EVENT)))
                    .toList();
            List<PostOutcome> results = new ArrayList<>(futures.size());
            for (Future<PostOutcome> future : futures) {
                try {
                    results.add(future.get(120, TimeUnit.SECONDS));
                } catch (Exception e) {
                    results.add(new PostOutcome("unknown", -1, 0, e.getMessage(), 0));
                }
            }
            return results;
        }
    }

    private static void redeliverViaKafka(String bootstrap, UUID tenantId, UUID resourceId, List<String> keys) {
        try (RedeliverySimulator redelivery = new RedeliverySimulator(bootstrap)) {
            Instant now = Instant.now();
            for (String key : keys) {
                redelivery.redeliver(new UsageEventMessage(key, tenantId, resourceId, 1, now, now,
                        "val-redelivery-" + UUID.randomUUID()));
            }
        }
    }

    private record StabilizedCount(CountSum countSum, long elapsedMillis) {
    }

    private static StabilizedCount pollUntilStable(Database db, UUID tenantId, UUID resourceId) {
        long start = System.nanoTime();
        CountSum previous = null;
        int stableRounds = 0;
        for (int i = 0; i < 60 && stableRounds < 3; i++) {
            CountSum current;
            try {
                current = db.runAsTenant(tenantId, c -> db.countAndSumForResource(c, resourceId));
            } catch (SQLException e) {
                throw new HarnessException("Polling ledger failed: " + e.getMessage(), e);
            }
            stableRounds = current.equals(previous) ? stableRounds + 1 : 0;
            previous = current;
            if (stableRounds < 3) {
                sleep(500);
            }
        }
        return new StabilizedCount(previous, (System.nanoTime() - start) / 1_000_000);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
