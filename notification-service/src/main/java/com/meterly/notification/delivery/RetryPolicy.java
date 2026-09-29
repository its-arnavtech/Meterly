package com.meterly.notification.delivery;

import com.meterly.notification.config.NotificationProperties;
import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * When a failed send is attempted again: {@code initial-backoff × 2^(attempts − 1)}, capped at
 * {@code max-backoff}, until {@code max-attempts} sends have been made. Also supplies the lease given to
 * a PENDING claim ({@code pending-timeout}).
 */
@Component
public class RetryPolicy {

    private static final int MAX_DOUBLINGS = 30;

    private final NotificationProperties.Retry retry;

    public RetryPolicy(NotificationProperties properties) {
        this.retry = properties.retry();
    }

    /** Delay before the next attempt after {@code attemptsSoFar} failed attempts; empty when none is left. */
    public Optional<Duration> backoffAfter(int attemptsSoFar) {
        if (attemptsSoFar >= retry.maxAttempts()) {
            return Optional.empty();
        }
        int doublings = Math.min(Math.max(attemptsSoFar - 1, 0), MAX_DOUBLINGS);
        Duration delay = retry.initialBackoff().multipliedBy(1L << doublings);
        return Optional.of(delay.compareTo(retry.maxBackoff()) > 0 ? retry.maxBackoff() : delay);
    }

    /** How long a PENDING claim is trusted to be in flight before the retry job takes it over. */
    public Duration pendingLease() {
        return retry.pendingTimeout();
    }

    public int maxAttempts() {
        return retry.maxAttempts();
    }

    public int batchSize() {
        return retry.batchSize();
    }
}
