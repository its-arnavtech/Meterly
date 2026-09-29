package com.meterly.tenant.internal;

import com.meterly.tenant.apikey.ApiKeyService;
import com.meterly.tenant.support.Items;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service endpoints. Access requires the shared {@code X-Internal-Token} (see
 * {@code SecurityConfiguration}); the tenant comes from the API key or the path, never from a user token.
 */
@RestController
@RequestMapping("/internal/v1")
public class InternalController {

    private final ApiKeyService apiKeys;
    private final TenantDirectoryService directory;

    public InternalController(ApiKeyService apiKeys, TenantDirectoryService directory) {
        this.apiKeys = apiKeys;
        this.directory = directory;
    }

    public record VerifyApiKeyRequest(@NotBlank @Size(max = 256) String apiKey) {

        @Override
        public String toString() {
            return "VerifyApiKeyRequest[apiKey=<redacted>]";
        }
    }

    public record TenantResources(UUID tenantId, List<UUID> resourceIds) {
    }

    public record AdminRecipient(String email) {
    }

    @PostMapping("/api-keys/verify")
    public ApiKeyService.Verification verify(@Valid @RequestBody VerifyApiKeyRequest request) {
        return apiKeys.verify(request.apiKey());
    }

    @GetMapping("/tenants/{tenantId}/resources")
    public TenantResources resources(@PathVariable UUID tenantId) {
        return new TenantResources(tenantId, directory.activeResourceIds(tenantId));
    }

    @GetMapping("/tenants/{tenantId}/admins")
    public Items<AdminRecipient> admins(@PathVariable UUID tenantId) {
        return Items.of(directory.activeAdminEmails(tenantId).stream().map(AdminRecipient::new).toList());
    }
}
