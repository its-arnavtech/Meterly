package com.meterly.tenant.resource;

import java.time.Instant;
import java.util.UUID;

/** Contract "Resource" object: a metered resource, {@code { id, name, unit_label, status, created_at }}. */
public record Resource(UUID id, String name, String unitLabel, ResourceStatus status, Instant createdAt) {

    public enum ResourceStatus {
        ACTIVE,
        ARCHIVED
    }
}
