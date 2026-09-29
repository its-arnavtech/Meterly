package com.meterly.tenant.throttle;

import com.meterly.common.web.ProblemDetails;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Adds {@code Retry-After} to throttling errors. Ordered ahead of the shared {@code GlobalExceptionHandler},
 * which renders the same problem+json body but has no way to set response headers.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TooManyRequestsAdvice {

    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<ProblemDetail> handle(TooManyRequestsException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetails.of(ex.status(), ex.code(), ex.getMessage(), request.getRequestURI());
        ex.properties().forEach(problem::setProperty);
        return ResponseEntity.status(ex.status())
                .header(HttpHeaders.RETRY_AFTER, Long.toString(ex.retryAfterSeconds()))
                .body(problem);
    }
}
