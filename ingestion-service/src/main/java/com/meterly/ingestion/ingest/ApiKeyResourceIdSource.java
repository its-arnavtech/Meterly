package com.meterly.ingestion.ingest;

import com.meterly.ingestion.security.ApiKeyAuthentication;
import com.meterly.ingestion.tenant.ApiKeyAuthCache;
import java.util.Set;
import java.util.UUID;

/** Resource ids for an API-key caller, starting from what the (possibly cached) verification already
 * returned, with a bypass-cache refresh for the unknown-resource path. */
final class ApiKeyResourceIdSource implements ResourceIdSource {

    private final ApiKeyAuthCache cache;
    private final ApiKeyAuthentication authentication;
    private Set<UUID> resourceIds;

    ApiKeyResourceIdSource(ApiKeyAuthCache cache, ApiKeyAuthentication authentication) {
        this.cache = cache;
        this.authentication = authentication;
        this.resourceIds = authentication.resourceIds();
    }

    @Override
    public boolean contains(UUID resourceId) {
        return resourceIds.contains(resourceId);
    }

    @Override
    public boolean refreshContains(UUID resourceId) {
        resourceIds = cache.refresh((String) authentication.getCredentials()).resourceIds();
        return resourceIds.contains(resourceId);
    }
}
