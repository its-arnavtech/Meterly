package com.meterly.tenant.throttle;

import com.meterly.common.web.ApiException;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** 429 with a {@code Retry-After} header, written by {@link TooManyRequestsAdvice}. */
public class TooManyRequestsException extends ApiException {

    private final long retryAfterSeconds;

    private TooManyRequestsException(String code, String message, long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, code, message, Map.of("retry_after_seconds", retryAfterSeconds));
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** Login throttling ({@code TOO_MANY_ATTEMPTS}). */
    public static TooManyRequestsException attempts(long retryAfterSeconds) {
        return new TooManyRequestsException("TOO_MANY_ATTEMPTS",
                "Too many sign-in attempts; try again later", retryAfterSeconds);
    }

    /** Signup throttling ({@code TOO_MANY_REQUESTS}). */
    public static TooManyRequestsException requests(long retryAfterSeconds) {
        return new TooManyRequestsException("TOO_MANY_REQUESTS",
                "Too many requests; try again later", retryAfterSeconds);
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
