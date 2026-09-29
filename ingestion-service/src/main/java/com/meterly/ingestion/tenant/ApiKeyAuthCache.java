package com.meterly.ingestion.tenant;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.meterly.common.crypto.Hashing;
import com.meterly.common.web.ApiException;
import com.meterly.ingestion.config.IngestionProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Caffeine cache of API-key verification results, keyed by the SHA-256 of the presented key (the raw key
 * is never held longer than needed for the outbound call). A verified key is cached for
 * {@code meterly.ingestion.auth-cache.positive-ttl} (default 60s); an unknown/revoked key is cached
 * separately for the shorter {@code negative-ttl} (default 10s) so a guessing attacker cannot force a
 * tenant-service call per attempt, while a freshly revoked key stops working reasonably quickly.
 */
@Component
public class ApiKeyAuthCache {

    private final TenantVerificationClient client;
    private final MeterRegistry metrics;
    private final Cache<String, ApiKeyVerification> positive;
    private final Cache<String, Boolean> negative;

    public ApiKeyAuthCache(TenantVerificationClient client, MeterRegistry metrics, IngestionProperties properties) {
        this.client = client;
        this.metrics = metrics;
        this.positive = Caffeine.newBuilder()
                .expireAfterWrite(properties.authCache().positiveTtl())
                .maximumSize(10_000)
                .build();
        this.negative = Caffeine.newBuilder()
                .expireAfterWrite(properties.authCache().negativeTtl())
                .maximumSize(10_000)
                .build();
    }

    /** Verifies {@code apiKey}, serving a cached result when available. */
    public ApiKeyVerification verify(String apiKey) {
        String cacheKey = Hashing.sha256Hex(apiKey);
        ApiKeyVerification cached = positive.getIfPresent(cacheKey);
        if (cached != null) {
            metrics.counter("meterly.ingestion.auth.cache", "result", "hit").increment();
            return cached;
        }
        if (Boolean.TRUE.equals(negative.getIfPresent(cacheKey))) {
            metrics.counter("meterly.ingestion.auth.cache", "result", "hit").increment();
            throw ApiException.unauthorized("INVALID_API_KEY", "Invalid API key");
        }
        metrics.counter("meterly.ingestion.auth.cache", "result", "miss").increment();
        return fetchAndCache(apiKey, cacheKey);
    }

    /** Bypasses both caches; used once when a resource id is unknown against the cached resource set,
     * in case the resource was created after the cached verification. */
    public ApiKeyVerification refresh(String apiKey) {
        return fetchAndCache(apiKey, Hashing.sha256Hex(apiKey));
    }

    private ApiKeyVerification fetchAndCache(String apiKey, String cacheKey) {
        try {
            ApiKeyVerification verification = client.verify(apiKey);
            positive.put(cacheKey, verification);
            negative.invalidate(cacheKey);
            return verification;
        } catch (ApiException e) {
            if (e.status() == HttpStatus.UNAUTHORIZED) {
                negative.put(cacheKey, Boolean.TRUE);
            }
            throw e;
        }
    }
}
