package com.meterly.validation.load;

import com.meterly.validation.checks.IngestionClient;
import com.meterly.validation.cli.HarnessArgs;
import com.meterly.validation.http.ApiClient;
import com.meterly.validation.http.Auth;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * {@code load} sub-command: a steady-rate synthetic traffic generator against
 * {@code POST /v1/usage-events}, one virtual thread per request (docs/why-java.md §5). Not part of
 * the pass/fail exit-criteria checks — a standalone tool for soak-testing an already-running stack.
 *
 * <pre>
 *   java -jar meterly-validation.jar load --base-url http://localhost:8080 \
 *       --api-key mk_live_... --resource-id &lt;uuid&gt; --rps 20 --duration 60
 * </pre>
 */
public final class LoadCommand {

    private LoadCommand() {
    }

    public static int run(HarnessArgs args) {
        String baseUrl = args.get("base-url", "http://localhost:8080");
        double rps = args.getDouble("rps", 10.0);
        int durationSeconds = args.getInt("duration", 30);
        String apiKey = args.require("api-key");
        UUID resourceId = UUID.fromString(args.require("resource-id"));

        ApiClient api = new ApiClient(baseUrl);
        Auth auth = Auth.apiKey(apiKey);
        long intervalNanos = Math.max(1, (long) (1_000_000_000.0 / rps));
        Instant deadline = Instant.now().plusSeconds(durationSeconds);

        AtomicLong sent = new AtomicLong();
        AtomicLong accepted = new AtomicLong();
        AtomicLong duplicate = new AtomicLong();
        AtomicLong rateLimited = new AtomicLong();
        AtomicLong failed = new AtomicLong();

        System.out.printf("Load: %.1f rps for %ds against %s/v1/usage-events%n", rps, durationSeconds, baseUrl);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            long nextTick = System.nanoTime();
            while (Instant.now().isBefore(deadline)) {
                long now = System.nanoTime();
                if (now < nextTick) {
                    LockSupport.parkNanos(Math.min(nextTick - now, 5_000_000));
                    continue;
                }
                nextTick += intervalNanos;
                sent.incrementAndGet();
                executor.submit(() -> {
                    try {
                        var outcome = IngestionClient.postOnce(api, auth, resourceId, "load-" + UUID.randomUUID(), 1);
                        switch (outcome.status()) {
                            case 202 -> accepted.incrementAndGet();
                            case 200 -> duplicate.incrementAndGet();
                            case 429 -> rateLimited.incrementAndGet();
                            default -> failed.incrementAndGet();
                        }
                    } catch (RuntimeException e) {
                        failed.incrementAndGet();
                    }
                });
            }
            executor.shutdown();
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                System.err.println("Load: in-flight requests did not finish within 30s of the deadline");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.printf("Load result: sent=%d accepted=%d duplicate=%d rate_limited=%d failed=%d%n",
                sent.get(), accepted.get(), duplicate.get(), rateLimited.get(), failed.get());
        return failed.get() > 0 ? 1 : 0;
    }
}
