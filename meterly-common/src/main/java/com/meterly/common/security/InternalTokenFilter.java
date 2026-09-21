package com.meterly.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates service-to-service calls on {@code /internal/**} using the shared
 * {@value #HEADER} secret (compared in constant time). A matching request receives
 * {@code ROLE_INTERNAL}; a missing or wrong token leaves the request unauthenticated so the
 * security chain rejects it with 401.
 *
 * <p>Register with {@code http.addFilterBefore(new InternalTokenFilter(token), BearerTokenAuthenticationFilter.class)}
 * and authorize {@code /internal/**} with {@code hasRole("INTERNAL")}.
 */
public class InternalTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Token";
    public static final String ROLE = "INTERNAL";
    public static final String PATH_PREFIX = "/internal/";

    private final byte[] expected;

    public InternalTokenFilter(String expectedToken) {
        if (expectedToken == null || expectedToken.length() < 16) {
            throw new IllegalArgumentException("meterly.security.internal-token must be at least 16 characters");
        }
        this.expected = expectedToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(request.getContextPath() + PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presented = request.getHeader(HEADER);
        if (presented != null
                && MessageDigest.isEqual(expected, presented.getBytes(StandardCharsets.UTF_8))) {
            var auth = new UsernamePasswordAuthenticationToken(
                    "internal-service", null, List.of(new SimpleGrantedAuthority("ROLE_" + ROLE)));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        chain.doFilter(request, response);
    }
}
