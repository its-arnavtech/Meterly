package com.meterly.common.tenant;

import java.util.UUID;

/**
 * Implemented by non-JWT {@link org.springframework.security.core.Authentication} types (for example the
 * ingestion service's API-key authentication) so {@link TenantContextAspect} can resolve their tenant.
 */
public interface TenantAuthentication {

    UUID tenantId();
}
