package com.meterly.platform.auth;

import com.meterly.platform.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

public final class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        boolean bearerSupplied = header != null && header.startsWith("Bearer ");
        Optional<AuthPrincipal> principal = bearerSupplied
                ? jwtService.verify(header.substring(7)) : Optional.empty();

        if (principal.isEmpty()) {
            if (bearerSupplied) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid bearer token");
                return;
            }
            filterChain.doFilter(request, response);
            return;
        }

        AuthPrincipal authenticated = principal.get();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        authenticated,
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + authenticated.role()))
                ));
        try (TenantContext.Scope ignored = TenantContext.open(authenticated.tenantId())) {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
