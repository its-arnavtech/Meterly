package com.meterly.ingestion.tenant;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.meterly.ingestion.config.IngestionProperties;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Caffeine cache of a tenant's active resource ids, used for JWT-admin callers (whose token does not
 * carry resource information). TTL is {@code meterly.ingestion.resource-cache-ttl} (default 60s).
 */
@Component
public class TenantResourceCache {

    private final TenantVerificationClient client;
    private final Cache<UUID, Set<UUID>> cache;

    public TenantResourceCache(TenantVerificationClient client, IngestionProperties properties) {
        this.client = client;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(properties.resourceCacheTtl())
                .maximumSize(10_000)
                .build();
    }

    public Set<UUID> resourceIds(UUID tenantId) {
        Set<UUID> cached = cache.getIfPresent(tenantId);
        return cached != null ? cached : refresh(tenantId);
    }

    /** Bypasses the cache once; used when a resource id is unknown against the cached set. */
    public Set<UUID> refresh(UUID tenantId) {
        Set<UUID> fresh = client.resources(tenantId);
        cache.put(tenantId, fresh);
        return fresh;
    }
}
