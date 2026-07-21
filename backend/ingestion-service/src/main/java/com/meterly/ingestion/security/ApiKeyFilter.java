package com.meterly.ingestion.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

@Component
public class ApiKeyFilter extends OncePerRequestFilter {
    private final byte[] expectedApiKey;

    public ApiKeyFilter(@Value("${meterly.ingestion.api-key}") String expectedApiKey) {
        this.expectedApiKey = expectedApiKey.getBytes(StandardCharsets.UTF_8);
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
        boolean matches = supplied != null && MessageDigest.isEqual(
                supplied.getBytes(StandardCharsets.UTF_8), expectedApiKey);
        if (!matches) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Valid X-Api-Key required");
            return;
        }

        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("poc-api-key", null, List.of()));
        filterChain.doFilter(request, response);
    }
}

