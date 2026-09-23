package com.meterly.validation.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response shapes from tenant-service (docs/api-contract.md §1). Records deserialize directly via
 * Jackson's constructor-based binding (the root POM compiles with {@code -parameters}); unknown JSON
 * properties are ignored so the harness tolerates additive server-side changes.
 */
public final class TenantModels {
    private TenantModels() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TenantInfo(UUID id, String name, String status, Instant createdAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UserInfo(UUID id, String email, String role, String status, Instant createdAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ResourceInfo(UUID id, String name, String unitLabel, String status, Instant createdAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ResourcesResponse(List<ResourceInfo> items) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SignupResponse(TenantInfo tenant, UserInfo admin, Object subscription, List<ResourceInfo> resources) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UserSummary(UUID id, String email, String role, UUID tenantId, String tenantName) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LoginResponse(String accessToken, String tokenType, long expiresIn, UserSummary user) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MeResponse(UserInfo user, TenantInfo tenant) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ApiKeyCreated(UUID id, String name, String prefix, Instant createdAt, String key) {
    }
}
