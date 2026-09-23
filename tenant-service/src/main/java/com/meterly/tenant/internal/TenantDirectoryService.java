package com.meterly.tenant.internal;

import com.meterly.common.web.ApiException;
import com.meterly.tenant.account.Tenant;
import com.meterly.tenant.account.TenantRepository;
import com.meterly.tenant.resource.ResourceRepository;
import com.meterly.tenant.support.TenantTransactions;
import com.meterly.tenant.user.UserRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Read-only tenant lookups for other services ({@code /internal/**}); each runs in the named tenant. */
@Service
public class TenantDirectoryService {

    private final TenantRepository tenants;
    private final ResourceRepository resources;
    private final UserRepository users;
    private final TenantTransactions transactions;

    public TenantDirectoryService(TenantRepository tenants, ResourceRepository resources, UserRepository users,
                                  TenantTransactions transactions) {
        this.tenants = tenants;
        this.resources = resources;
        this.users = users;
        this.transactions = transactions;
    }

    /** Active resource ids of an active tenant; 404 for unknown or suspended tenants. */
    public List<UUID> activeResourceIds(UUID tenantId) {
        return transactions.inTenant(tenantId, () -> {
            requireActiveTenant(tenantId);
            return resources.activeIds();
        });
    }

    /** Emails of the tenant's active admins (notification recipients); 404 for unknown or suspended tenants. */
    public List<String> activeAdminEmails(UUID tenantId) {
        return transactions.inTenant(tenantId, () -> {
            requireActiveTenant(tenantId);
            return users.activeAdminEmails();
        });
    }

    private void requireActiveTenant(UUID tenantId) {
        tenants.find(tenantId)
                .filter(Tenant::isActive)
                .orElseThrow(() -> ApiException.notFound("TENANT_NOT_FOUND", "Tenant " + tenantId + " not found"));
    }
}
