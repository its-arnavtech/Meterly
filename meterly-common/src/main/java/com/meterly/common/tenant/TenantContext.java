package com.meterly.common.tenant;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Holds the tenant on whose behalf the current thread is working.
 *
 * <p>The value is read by {@link TenantAwareTransactionManager} at the start of every database
 * transaction and pushed into PostgreSQL as the transaction-local setting {@code app.tenant_id},
 * which the Row-Level Security policies compare against. Binding is always scoped: callers use
 * {@link #callAs} / {@link #runAs}, which restore the previous value in a {@code finally} block, so a
 * pooled platform thread or a reused virtual-thread carrier can never observe a stale tenant.
 *
 * <p>Request threads are bound by {@link TenantContextAspect}; Kafka consumers and schedulers bind
 * explicitly with {@code TenantContext.runAs(tenantId, () -> ...)}. One tenant per transaction: never
 * switch tenants inside an open transaction, open a new transaction per tenant instead.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static Optional<UUID> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** Returns the bound tenant or throws {@link MissingTenantContextException}. */
    public static UUID require() {
        UUID tenantId = CURRENT.get();
        if (tenantId == null) {
            throw new MissingTenantContextException();
        }
        return tenantId;
    }

    public static <T> T callAs(UUID tenantId, Supplier<T> action) {
        Objects.requireNonNull(tenantId, "tenantId");
        UUID previous = CURRENT.get();
        CURRENT.set(tenantId);
        try {
            return action.get();
        } finally {
            restore(previous);
        }
    }

    public static void runAs(UUID tenantId, Runnable action) {
        callAs(tenantId, () -> {
            action.run();
            return null;
        });
    }

    /** Variant for code that throws checked exceptions (used by the request aspect). */
    public static <T> T callAsChecked(UUID tenantId, Callable<T> action) throws Exception {
        Objects.requireNonNull(tenantId, "tenantId");
        UUID previous = CURRENT.get();
        CURRENT.set(tenantId);
        try {
            return action.call();
        } finally {
            restore(previous);
        }
    }

    private static void restore(UUID previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
