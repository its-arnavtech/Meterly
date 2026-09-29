package com.meterly.tenant.account;

import java.time.Instant;
import java.util.UUID;

/** Contract "Tenant" object: {@code { id, name, status, created_at }}. */
public record Tenant(UUID id, String name, TenantStatus status, Instant createdAt) {

    public enum TenantStatus {
        ACTIVE,
        SUSPENDED
    }

    public boolean isActive() {
        return status == TenantStatus.ACTIVE;
    }
}
