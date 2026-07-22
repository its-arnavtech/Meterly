package com.meterly.platform.tenant;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

@Aspect
public final class TenantTransactionAspect {
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public TenantTransactionAspect(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    @Around("@annotation(com.meterly.platform.tenant.TenantTransactional) || "
            + "@within(com.meterly.platform.tenant.TenantTransactional)")
    public Object withinTenantTransaction(ProceedingJoinPoint joinPoint) {
        UUID tenantId = TenantContext.requireTenant();
        return transactionTemplate.execute(status -> {
            jdbcTemplate.queryForObject(
                    "select set_config('app.tenant_id', ?, true)",
                    String.class,
                    tenantId.toString()
            );
            try {
                return joinPoint.proceed();
            } catch (RuntimeException | Error exception) {
                throw exception;
            } catch (Throwable throwable) {
                throw new IllegalStateException("Tenant transaction failed", throwable);
            }
        });
    }
}
