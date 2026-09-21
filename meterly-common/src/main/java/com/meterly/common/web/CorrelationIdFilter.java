package com.meterly.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/** Assigns every request a correlation ID, exposes it in the MDC and echoes it in the response. */
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = CorrelationId.sanitizeOrGenerate(request.getHeader(CorrelationId.HTTP_HEADER));
        MDC.put(CorrelationId.MDC_KEY, requestId);
        response.setHeader(CorrelationId.HTTP_HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }
}
