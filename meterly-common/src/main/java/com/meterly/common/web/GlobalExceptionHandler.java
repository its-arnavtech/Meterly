package com.meterly.common.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Renders every error as {@code application/problem+json} with a stable {@code code} and the
 * {@code request_id}. Unexpected exceptions are logged with their stack trace and returned as an opaque
 * 500 so internals never leak to clients.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApi(ApiException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetails.of(ex.status(), ex.code(), ex.getMessage(), request.getRequestURI());
        ex.properties().forEach(problem::setProperty);
        return ResponseEntity.status(ex.status()).body(problem);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex,
                                                                   HttpServletRequest request) {
        List<Map<String, String>> errors = ex.getConstraintViolations().stream()
                .map(v -> Map.of("field", String.valueOf(v.getPropertyPath()), "message", v.getMessage()))
                .toList();
        ProblemDetail problem = ProblemDetails.of(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "Request validation failed", request.getRequestURI());
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ProblemDetail> handleDuplicate(DuplicateKeyException ex, HttpServletRequest request) {
        log.info("Unique constraint violation");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ProblemDetails.of(HttpStatus.CONFLICT, "CONFLICT",
                "The resource conflicts with an existing one", request.getRequestURI()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ProblemDetails.of(HttpStatus.FORBIDDEN, "FORBIDDEN",
                "You do not have permission to perform this action", request.getRequestURI()));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex,
                                                              HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .body(ProblemDetails.of(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                        "Authentication is required to access this resource", request.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
        // SQL and provider exception messages can contain row contents or credentials.
        log.error("Unhandled exception on {} {}: {}", request.getMethod(), request.getRequestURI(),
                ex.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(ProblemDetails.of(HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR", "An unexpected error occurred", request.getRequestURI()));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> Map.of("field", e.getField(),
                        "message", e.getDefaultMessage() == null ? "is invalid" : e.getDefaultMessage()))
                .toList();
        ProblemDetail problem = ProblemDetails.of(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "Request validation failed", path(request));
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    /** Framework-level errors (malformed JSON, unsupported media type, 404 routes, ...) get a code too. */
    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers,
                                                          HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem
                && (problem.getProperties() == null || !problem.getProperties().containsKey("code"))) {
            String code = switch (statusCode.value()) {
                case 400 -> "BAD_REQUEST";
                case 404 -> "NOT_FOUND";
                case 405 -> "METHOD_NOT_ALLOWED";
                case 406 -> "NOT_ACCEPTABLE";
                case 413 -> "PAYLOAD_TOO_LARGE";
                case 415 -> "UNSUPPORTED_MEDIA_TYPE";
                default -> statusCode.is5xxServerError() ? "INTERNAL_ERROR" : "REQUEST_ERROR";
            };
            problem.setProperty("code", code);
            CorrelationId.current().ifPresent(id -> problem.setProperty("request_id", id));
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private static String path(WebRequest request) {
        return request instanceof ServletWebRequest swr ? swr.getRequest().getRequestURI() : null;
    }
}
