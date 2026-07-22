package com.meterly.tenant.security;

import com.meterly.platform.tenant.TenantContext;
import com.meterly.tenant.tenant.TenantRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
public class ApiKeyService {
    private final TenantRepository tenantRepository;

    public ApiKeyService(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    public Optional<InternalApiKeyController.IntrospectionResponse> introspect(String rawKey) {
        Optional<UUID> tenantId = tenantRepository.lookupTenantByApiKeyHash(ApiKeyMaterial.hash(rawKey));
        if (tenantId.isEmpty()) {
            return Optional.empty();
        }
        try (TenantContext.Scope ignored = TenantContext.open(tenantId.get())) {
            return Optional.of(new InternalApiKeyController.IntrospectionResponse(
                    tenantId.get(), tenantRepository.listResourceIds()));
        }
    }
}
