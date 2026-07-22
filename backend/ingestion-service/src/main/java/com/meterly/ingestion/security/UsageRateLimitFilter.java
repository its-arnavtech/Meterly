package com.meterly.ingestion.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class UsageRateLimitFilter extends OncePerRequestFilter {
    private final TenantRateLimiter rateLimiter;

    public UsageRateLimitFilter(TenantRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!"POST".equals(request.getMethod()) || !request.getRequestURI().startsWith("/v1/usage-events")) {
            filterChain.doFilter(request, response);
            return;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof ApiKeyPrincipal principal)) {
            filterChain.doFilter(request, response);
            return;
        }
        TenantRateLimiter.Decision decision = rateLimiter.consume(principal.tenantId());
        if (!decision.allowed()) {
            response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
            response.sendError(429, "Tenant usage-ingestion rate limit exceeded");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
