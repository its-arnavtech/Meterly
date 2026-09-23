package com.meterly.ingestion.security;

import com.meterly.common.tenant.TenantAuthentication;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Authentication for a machine caller verified via {@code X-API-Key}. Implements
 * {@link TenantAuthentication} so {@link com.meterly.common.tenant.TenantContextAspect} can bind the
 * tenant for MDC/logging just as it does for a JWT, even though this service has no database transaction
 * to attach row-level security to.
 *
 * <p>The raw API key is kept as the credentials so the unknown-resource "refresh once" path can bypass
 * the verification cache and ask tenant-service again without needing the header a second time.
 */
public final class ApiKeyAuthentication extends AbstractAuthenticationToken implements TenantAuthentication {

    private final UUID tenantId;
    private final UUID apiKeyId;
    private final Set<UUID> resourceIds;
    private final String apiKey;

    public ApiKeyAuthentication(UUID tenantId, UUID apiKeyId, Set<UUID> resourceIds, String apiKey) {
        super(List.of(new SimpleGrantedAuthority("ROLE_API_KEY")));
        this.tenantId = tenantId;
        this.apiKeyId = apiKeyId;
        this.resourceIds = Set.copyOf(resourceIds);
        this.apiKey = apiKey;
        setAuthenticated(true);
    }

    @Override
    public UUID tenantId() {
        return tenantId;
    }

    public UUID apiKeyId() {
        return apiKeyId;
    }

    public Set<UUID> resourceIds() {
        return resourceIds;
    }

    /** The verified raw key, kept only so a refresh can be requested without a second header round trip. */
    @Override
    public Object getCredentials() {
        return apiKey;
    }

    @Override
    public Object getPrincipal() {
        return tenantId;
    }
}
