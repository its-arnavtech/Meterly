package com.meterly.validation.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** RFC 7807 {@code application/problem+json} error body (docs/api-contract.md §0 "Errors"). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Problem(String type, String title, int status, String detail, String instance, String code,
                       String requestId) {
}
