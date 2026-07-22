package com.meterly.ingestion.security;

import java.util.Set;
import java.util.UUID;

public record ApiKeyPrincipal(UUID tenantId, Set<UUID> resourceIds) {
}
