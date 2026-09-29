package com.meterly.notification.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * notification-service settings under {@code meterly.notification.*} (every value is overridable by
 * environment variable, e.g. {@code METERLY_NOTIFICATION_RETRY_MAX_ATTEMPTS}; the common ones have short
 * aliases in {@code application.yml}).
 *
 * <pre>
 * email.provider            logging (default, stubbed provider) | smtp          env EMAIL_PROVIDER
 * email.from                From address (default billing@meterly.local)      env EMAIL_FROM
 * consumer.max-retries      in-place Kafka retries for transient errors before the record goes to the DLT
 * consumer.initial-backoff  first retry delay, then x consumer.multiplier up to consumer.max-backoff
 * retry.enabled             run the scheduled re-delivery job                  env NOTIFICATION_RETRY_ENABLED
 * retry.interval            how often the job looks for due rows
 * retry.pending-timeout     lease of a PENDING claim; after it the sender is presumed dead and the row is re-sent
 * retry.initial-backoff     delay before re-sending a FAILED row, doubled per attempt up to retry.max-backoff
 * retry.max-attempts        total send attempts per notification before it stays FAILED
 * retry.batch-size          rows re-claimed per tenant per run
 * </pre>
 */
@Validated
@ConfigurationProperties("meterly.notification")
public record NotificationProperties(
        @Valid @DefaultValue Email email,
        @Valid @DefaultValue Consumer consumer,
        @Valid @DefaultValue Retry retry) {

    public enum Provider {
        /** Stubbed provider (PDD): logs a redacted summary instead of sending. */
        LOGGING,
        /** Real SMTP via Spring's {@code JavaMailSender} (Mailpit in Docker Compose). */
        SMTP
    }

    public record Email(
            @NotNull @DefaultValue("logging") Provider provider,
            @NotBlank @jakarta.validation.constraints.Email @DefaultValue("billing@meterly.local") String from) {
    }

    public record Consumer(
            @Min(0) @Max(20) @DefaultValue("5") int maxRetries,
            @NotNull @DefaultValue("1s") Duration initialBackoff,
            @DecimalMin("1.0") @DefaultValue("2.0") double multiplier,
            @NotNull @DefaultValue("30s") Duration maxBackoff) {
    }

    public record Retry(
            @DefaultValue("true") boolean enabled,
            @NotNull @DefaultValue("60s") Duration interval,
            @NotNull @DefaultValue("5m") Duration pendingTimeout,
            @NotNull @DefaultValue("1m") Duration initialBackoff,
            @NotNull @DefaultValue("1h") Duration maxBackoff,
            @Min(1) @Max(50) @DefaultValue("5") int maxAttempts,
            @Min(1) @Max(1000) @DefaultValue("50") int batchSize) {
    }
}
