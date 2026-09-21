package com.meterly.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meterly.common.web.ProblemDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Writes RFC 7807 bodies for 401/403 raised by the security filter chain, so clients get the same
 * error shape as for controller errors. Wire with
 * {@code http.exceptionHandling(e -> e.authenticationEntryPoint(h.entryPoint()).accessDeniedHandler(h.accessDeniedHandler()))}
 * and also {@code oauth2ResourceServer(o -> o.authenticationEntryPoint(h.entryPoint()))}.
 */
public class ProblemDetailsSecurityHandlers {

    private final ObjectMapper objectMapper;

    public ProblemDetailsSecurityHandlers(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AuthenticationEntryPoint entryPoint() {
        return (request, response, ex) -> write(request, response, HttpStatus.UNAUTHORIZED,
                "UNAUTHENTICATED", "Authentication is required to access this resource");
    }

    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, ex) -> write(request, response, HttpStatus.FORBIDDEN,
                "FORBIDDEN", "You do not have permission to perform this action");
    }

    private void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status,
                       String code, String detail) throws IOException {
        ProblemDetail problem = ProblemDetails.of(status, code, detail, request.getRequestURI());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        if (status == HttpStatus.UNAUTHORIZED) {
            response.setHeader("WWW-Authenticate", "Bearer");
        }
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
