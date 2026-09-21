package com.meterly.common.web;

import java.net.URI;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/** Builds the uniform RFC 7807 error body used by every Meterly service. */
public final class ProblemDetails {

    public static final String TYPE_PREFIX = "https://meterly.dev/problems/";

    private ProblemDetails() {
    }

    public static ProblemDetail of(HttpStatusCode status, String code, String detail, String instance) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_PREFIX + code.toLowerCase().replace('_', '-')));
        if (instance != null) {
            problem.setInstance(URI.create(instance));
        }
        problem.setProperty("code", code);
        CorrelationId.current().ifPresent(id -> problem.setProperty("request_id", id));
        return problem;
    }
}
