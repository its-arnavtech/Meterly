package com.meterly.validation.checks;

import com.meterly.validation.http.Auth;
import com.meterly.validation.model.TenantModels.ResourceInfo;
import java.util.List;
import java.util.UUID;

/** One of the three organizations the harness exercises (contract §9 item 1). */
public record Organization(String label, UUID tenantId, String adminEmail, String adminJwt, String apiKey,
                            List<ResourceInfo> resources) {

    public Auth bearer() {
        return Auth.bearer(adminJwt);
    }

    public Auth apiKeyAuth() {
        return Auth.apiKey(apiKey);
    }

    public ResourceInfo resourceNamed(String name) {
        return resources.stream().filter(r -> r.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalStateException(label + " has no resource named '" + name + "'"));
    }
}
