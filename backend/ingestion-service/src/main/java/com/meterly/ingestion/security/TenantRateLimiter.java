package com.meterly.ingestion.security;

import java.util.UUID;

public interface TenantRateLimiter {
    Decision consume(UUID tenantId);

    record Decision(boolean allowed, long retryAfterSeconds) {
        public static Decision granted() {
            return new Decision(true, 0);
        }

        public static Decision rejected(long retryAfterSeconds) {
            return new Decision(false, Math.max(1, retryAfterSeconds));
        }
    }
}
