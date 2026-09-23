package com.meterly.ingestion.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meterly.common.web.ApiException;
import com.meterly.common.web.ProblemDetails;
import com.meterly.ingestion.tenant.ApiKeyAuthCache;
import com.meterly.ingestion.tenant.ApiKeyVerification;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates {@code X-API-Key} requests to the ingestion endpoints. Runs as a servlet filter (not
 * inside the controller) specifically so the tenant is already bound in the {@code SecurityContext}
 * before {@link com.meterly.common.tenant.TenantContextAspect} runs: that aspect resolves the tenant
 * once, at the very start of the controller-method invocation, from whatever {@code Authentication} is
 * already present.
 *
 * <p>On a verification failure this filter writes the problem+json response itself and does not call
 * the rest of the chain, so the specific contract error code (401 INVALID_API_KEY / 503 AUTH_UNAVAILABLE)
 * survives instead of being flattened into a generic 401/403 by the security exception handlers.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";
    private static final String PATH_PREFIX = "/v1/usage-events";

    private final ApiKeyAuthCache authCache;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthenticationFilter(ApiKeyAuthCache authCache, ObjectMapper objectMapper) {
        this.authCache = authCache;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(request.getContextPath() + PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String apiKey = request.getHeader(HEADER);
        if (apiKey == null) {
            chain.doFilter(request, response);
            return;
        }
        if (request.getHeader(HttpHeaders.AUTHORIZATION) != null) {
            writeProblem(request, response, HttpStatus.BAD_REQUEST, "AMBIGUOUS_CREDENTIALS",
                    "Provide either an API key or a bearer token, not both");
            return;
        }
        try {
            ApiKeyVerification verification = authCache.verify(apiKey);
            ApiKeyAuthentication authentication = new ApiKeyAuthentication(
                    verification.tenantId(), verification.apiKeyId(), verification.resourceIds(), apiKey);
            SecurityContextHolder.getContext().setAuthentication(authentication);
            chain.doFilter(request, response);
        } catch (ApiException e) {
            writeProblem(request, response, e.status(), e.code(), e.getMessage());
        }
    }

    private void writeProblem(HttpServletRequest request, HttpServletResponse response, HttpStatus status,
            String code, String detail) throws IOException {
        ProblemDetail problem = ProblemDetails.of(status, code, detail, request.getRequestURI());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
