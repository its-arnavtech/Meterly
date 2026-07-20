package com.meterly.billing.tenant;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

@Aspect
@Component
public class TenantTransactionAspect {
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public TenantTransactionAspect(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    @Around("@annotation(com.meterly.billing.tenant.TenantTransactional) || " +
            "@within(com.meterly.billing.tenant.TenantTransactional)")
    public Object withinTenantTransaction(ProceedingJoinPoint joinPoint) {
        UUID tenantId = TenantContext.currentTenant()
                .orElseThrow(() -> new IllegalStateException("Tenant context is required"));

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

