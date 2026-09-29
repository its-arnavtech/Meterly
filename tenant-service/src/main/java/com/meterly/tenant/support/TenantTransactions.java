package com.meterly.tenant.support;

import com.meterly.common.tenant.TenantContext;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs work in one transaction bound to an explicit tenant, for flows where the tenant is not known from a
 * JWT: signup (a new tenant id), login/refresh (resolved through a SECURITY DEFINER lookup) and the
 * {@code /internal/**} endpoints (tenant id from the path or an API key).
 */
@Component
public class TenantTransactions {

    private final TransactionTemplate transactionTemplate;

    public TenantTransactions(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public <T> T inTenant(UUID tenantId, Supplier<T> work) {
        Objects.requireNonNull(tenantId, "tenantId");
        return TenantContext.callAs(tenantId, () -> transactionTemplate.execute(status -> work.get()));
    }

    public void runInTenant(UUID tenantId, Runnable work) {
        inTenant(tenantId, () -> {
            work.run();
            return null;
        });
    }
}
