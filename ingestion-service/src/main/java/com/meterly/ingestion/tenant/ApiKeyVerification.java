package com.meterly.ingestion.tenant;

import java.util.Set;
import java.util.UUID;

/**
 * Result of {@code POST /internal/v1/api-keys/verify} on tenant-service: the tenant the key belongs to,
 * the key's own id, and the tenant's currently active metered resources.
 */
public record ApiKeyVerification(UUID tenantId, UUID apiKeyId, Set<UUID> resourceIds) {
}
