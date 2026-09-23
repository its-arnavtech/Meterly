package com.meterly.ingestion.ratelimit;

/**
 * @param allowed          whether the requested cost fit in the bucket
 * @param limit            the tenant's configured bucket capacity
 * @param remaining        tokens left after this call (or the pre-refill level, when not allowed)
 * @param retryAfterSeconds seconds until enough tokens will have refilled; 0 when allowed
 */
public record RateLimitDecision(boolean allowed, long limit, long remaining, long retryAfterSeconds) {
}
