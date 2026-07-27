package com.meterly.tenant.tenant;

import com.meterly.platform.tenant.TenantContext;
import com.meterly.tenant.auth.IdentityProvider;
import com.meterly.tenant.security.ApiKeyMaterial;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class TenantManagementService {
    private final TenantRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final IdentityProvider identityProvider;

    public TenantManagementService(
            TenantRepository repository,
            PasswordEncoder passwordEncoder,
            IdentityProvider identityProvider
    ) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.identityProvider = identityProvider;
    }

    public OnboardingResponse onboard(
            String name,
            String adminEmail,
            String adminPassword,
            UUID planId
    ) {
        UUID tenantId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        String apiKey = ApiKeyMaterial.generate();
        String externalUserId = identityProvider.createUser(
                adminEmail, adminPassword, tenantId, "TENANT_ADMIN");
        try {
            try (TenantContext.Scope ignored = TenantContext.open(tenantId)) {
                repository.create(
                        tenantId,
                        name,
                        adminId,
                        adminEmail,
                        passwordEncoder.encode(adminPassword),
                        planId,
                        UUID.randomUUID(),
                        resourceId,
                        UUID.randomUUID(),
                        ApiKeyMaterial.hash(apiKey)
                );
            }
        } catch (RuntimeException exception) {
            identityProvider.deleteUser(externalUserId);
            throw exception;
        }
        return new OnboardingResponse(tenantId, adminId, adminEmail, resourceId, planId, apiKey);
    }

    public TenantRepository.UserView createUser(String email, String password, String role) {
        if (!List.of("TENANT_ADMIN", "TENANT_USER").contains(role)) {
            throw new IllegalArgumentException("role must be TENANT_ADMIN or TENANT_USER");
        }
        UUID tenantId = TenantContext.requireTenant();
        String externalUserId = identityProvider.createUser(email, password, tenantId, role);
        try {
            return repository.createUser(
                    UUID.randomUUID(), tenantId, email, role, passwordEncoder.encode(password));
        } catch (RuntimeException exception) {
            identityProvider.deleteUser(externalUserId);
            throw exception;
        }
    }

    public TenantRepository.ApiKeyView issueApiKey(String label) {
        UUID tenantId = TenantContext.requireTenant();
        String rawKey = ApiKeyMaterial.generate();
        return repository.createApiKey(
                UUID.randomUUID(), tenantId, label, ApiKeyMaterial.hash(rawKey), rawKey);
    }

    public record OnboardingResponse(
            UUID tenantId,
            UUID adminUserId,
            String adminEmail,
            UUID resourceId,
            UUID planId,
            String apiKey
    ) {
    }
}
