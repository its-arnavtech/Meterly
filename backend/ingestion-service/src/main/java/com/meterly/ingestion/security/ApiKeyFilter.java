package com.meterly.ingestion.security;

import com.meterly.platform.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@Component
public class ApiKeyFilter extends OncePerRequestFilter {
    private final TenantServiceClient tenantServiceClient;

    public ApiKeyFilter(TenantServiceClient tenantServiceClient) {
        this.tenantServiceClient = tenantServiceClient;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/v1/usage-events")) {
            filterChain.doFilter(request, response);
            return;
        }

        String supplied = request.getHeader("X-Api-Key");
        Optional<ApiKeyPrincipal> principal = supplied == null
                ? Optional.empty()
                : tenantServiceClient.introspect(supplied);
        if (principal.isEmpty()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Valid X-Api-Key required");
            return;
        }

        ApiKeyPrincipal authenticated = principal.get();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(authenticated, null, List.of()));
        try (TenantContext.Scope ignored = TenantContext.open(authenticated.tenantId())) {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
