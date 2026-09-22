package com.meterly.tenant.apikey;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.time.Instant;
import java.util.UUID;

/** Contract "ApiKey" object (never contains the key itself). */
public record ApiKey(UUID id, String name, String prefix, Instant createdAt, Instant lastUsedAt, Instant revokedAt) {

    /** Response of {@code POST /v1/api-keys}: the key's fields plus the raw {@code key}, returned only once. */
    public record Created(@JsonUnwrapped ApiKey apiKey, String key) {

        @Override
        public String toString() {
            return "ApiKey.Created[id=" + apiKey.id() + ", key=<redacted>]";
        }
    }
}
