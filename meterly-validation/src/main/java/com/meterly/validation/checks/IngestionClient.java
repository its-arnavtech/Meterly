package com.meterly.validation.checks;

import com.fasterxml.jackson.core.type.TypeReference;
import com.meterly.validation.http.ApiClient;
import com.meterly.validation.http.ApiResponse;
import com.meterly.validation.http.Auth;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Posts a single usage event to {@code POST /v1/usage-events}, honoring {@code 429} +
 * {@code Retry-After} by backing off and retrying (contract §9 item 2 and §2) rather than treating a
 * rate limit as a failure. Shared by the idempotent-metering check (a 950-event burst) and the
 * rate-limit check (a deliberate burst that is meant to trip 429s).
 */
public final class IngestionClient {

    private record EventRequest(UUID resourceId, String idempotencyKey, long quantity) {
    }

    public record PostOutcome(String idempotencyKey, int status, int attempts, String rawBody, long retryAfterSeconds) {
        public boolean succeeded() {
            return status == 202 || status == 200;
        }
    }

    private IngestionClient() {
    }

    public static PostOutcome postWithBackoff(ApiClient api, Auth auth, UUID resourceId, String idempotencyKey,
            long quantity, int maxAttempts) {
        int attempts = 0;
        while (attempts < maxAttempts) {
            attempts++;
            ApiResponse<Map<String, Object>> response = api.post("/v1/usage-events", auth,
                    new EventRequest(resourceId, idempotencyKey, quantity),
                    new TypeReference<Map<String, Object>>() {
                    });
            if (response.status() == 202 || response.status() == 200) {
                return new PostOutcome(idempotencyKey, response.status(), attempts, response.rawBody(), 0);
            }
            if (response.status() == 429) {
                long retryAfter = parseRetryAfter(response.header("Retry-After"));
                sleep(Duration.ofSeconds(retryAfter).plusMillis(ThreadLocalRandom.current().nextLong(50, 300)));
                continue;
            }
            return new PostOutcome(idempotencyKey, response.status(), attempts, response.rawBody(), 0);
        }
        return new PostOutcome(idempotencyKey, 429, attempts, "retries exhausted", 0);
    }

    /** A single attempt with no retry, for checks that need to observe the 429 itself. */
    public static PostOutcome postOnce(ApiClient api, Auth auth, UUID resourceId, String idempotencyKey, long quantity) {
        ApiResponse<Map<String, Object>> response = api.post("/v1/usage-events", auth,
                new EventRequest(resourceId, idempotencyKey, quantity), new TypeReference<Map<String, Object>>() {
                });
        long retryAfter = response.status() == 429 ? parseRetryAfter(response.header("Retry-After")) : 0;
        return new PostOutcome(idempotencyKey, response.status(), 1, response.rawBody(), retryAfter);
    }

    private static long parseRetryAfter(String header) {
        if (header == null) {
            return 1;
        }
        try {
            return Math.max(1, Long.parseLong(header.trim()));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
