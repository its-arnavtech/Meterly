package com.meterly.platform.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

public final class CorrelationIdFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Correlation-ID";
    public static final String MDC_KEY = "correlation_id";
    private static final Logger LOGGER = LoggerFactory.getLogger(CorrelationIdFilter.class);
    private static final Pattern SAFE_VALUE = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String supplied = request.getHeader(HEADER);
        String correlationId = supplied != null && SAFE_VALUE.matcher(supplied).matches()
                ? supplied
                : UUID.randomUUID().toString();
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            filterChain.doFilter(request, response);
            LOGGER.info(
                    "http_request method={} path={} status={}",
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus()
            );
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
