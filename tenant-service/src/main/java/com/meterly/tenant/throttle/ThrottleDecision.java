package com.meterly.tenant.throttle;

/** Outcome of taking one token from a bucket. */
public sealed interface ThrottleDecision {

    /** A token was available and has been consumed. */
    record Allowed() implements ThrottleDecision {
    }

    /** The bucket is empty; the next token is available after {@code retryAfterSeconds}. */
    record Limited(long retryAfterSeconds) implements ThrottleDecision {

        public Limited {
            retryAfterSeconds = Math.max(1, retryAfterSeconds);
        }
    }

    /** Redis could not be reached; the request is let through (fail open) and counted. */
    record Unavailable() implements ThrottleDecision {
    }
}
