package com.meterly.common.tenant;

import com.meterly.common.security.MeterlyClaims;
import java.util.Optional;
import java.util.UUID;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Wraps every {@code @RestController} invocation: reads the {@code tenant_id} claim from the validated
 * JWT (or the tenant of an API-key authentication) and binds it to {@link TenantContext} for the
 * duration of the call. {@link TenantAwareTransactionManager} then issues the transaction-local
 * {@code set_config('app.tenant_id', ..., true)} when the service layer opens a transaction.
 *
 * <p>Unauthenticated endpoints (login, signup, JWKS) proceed without a tenant; any tenant-scoped query
 * they attempted would match zero rows under RLS.
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantContextAspect {

    public static final String MDC_TENANT_ID = "tenant_id";

    @Around("@within(org.springframework.web.bind.annotation.RestController)")
    public Object bindTenant(ProceedingJoinPoint joinPoint) throws Throwable {
        Optional<UUID> tenant = resolveTenant(SecurityContextHolder.getContext().getAuthentication());
        if (tenant.isEmpty()) {
            return joinPoint.proceed();
        }
        String previousMdc = MDC.get(MDC_TENANT_ID);
        MDC.put(MDC_TENANT_ID, tenant.get().toString());
        try {
            return TenantContext.callAsChecked(tenant.get(), () -> {
                try {
                    return joinPoint.proceed();
                } catch (Exception | Error e) {
                    throw e;
                } catch (Throwable t) {
                    throw new IllegalStateException(t);
                }
            });
        } finally {
            if (previousMdc == null) {
                MDC.remove(MDC_TENANT_ID);
            } else {
                MDC.put(MDC_TENANT_ID, previousMdc);
            }
        }
    }

    /** Resolves the tenant from whichever authentication mechanism authenticated the request. */
    public static Optional<UUID> resolveTenant(Authentication authentication) {
        return switch (authentication) {
            case TenantAuthentication tenantAuth -> Optional.of(tenantAuth.tenantId());
            case JwtAuthenticationToken jwtAuth -> tenantClaim(jwtAuth.getToken());
            case null, default -> Optional.empty();
        };
    }

    private static Optional<UUID> tenantClaim(Jwt jwt) {
        String claim = jwt.getClaimAsString(MeterlyClaims.TENANT_ID);
        if (claim == null || claim.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(claim));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
