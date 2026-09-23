package com.meterly.validation.http;

import java.net.http.HttpHeaders;

/**
 * @param body   parsed response body, or {@code null} if the status/shape didn't match the requested
 *               type (callers that need the raw text on a mismatch use {@link #rawBody()})
 */
public record ApiResponse<T>(int status, HttpHeaders headers, T body, String rawBody) {

    public boolean is2xx() {
        return status >= 200 && status < 300;
    }

    public String header(String name) {
        return headers.firstValue(name).orElse(null);
    }
}
