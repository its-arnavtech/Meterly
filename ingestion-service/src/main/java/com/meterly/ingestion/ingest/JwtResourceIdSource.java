package com.meterly.ingestion.ingest;

import com.meterly.ingestion.tenant.TenantResourceCache;
import java.util.Set;
import java.util.UUID;

/** Resource ids for a JWT (TENANT_ADMIN) caller, looked up by tenant id since the token itself carries
 * no resource information. */
final class JwtResourceIdSource implements ResourceIdSource {

    private final TenantResourceCache cache;
    private final UUID tenantId;
    private Set<UUID> resourceIds;

    JwtResourceIdSource(TenantResourceCache cache, UUID tenantId) {
        this.cache = cache;
        this.tenantId = tenantId;
        this.resourceIds = cache.resourceIds(tenantId);
    }

    @Override
    public boolean contains(UUID resourceId) {
        return resourceIds.contains(resourceId);
    }

    @Override
    public boolean refreshContains(UUID resourceId) {
        resourceIds = cache.refresh(tenantId);
        return resourceIds.contains(resourceId);
    }
}
