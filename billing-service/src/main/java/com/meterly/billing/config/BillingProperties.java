package com.meterly.billing.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.validation.annotation.Validated;

/**
 * billing-service settings under {@code meterly.billing.*}. Every value can be overridden by environment
 * variable (relaxed binding, e.g. {@code METERLY_BILLING_OUTBOX_BATCH_SIZE}); the common ones also have
 * short aliases in {@code application.yml}.
 *
 * <pre>
 * close-cron             period-close schedule, Spring cron in UTC (default 0 0 2 1 * *)   env BILLING_CLOSE_CRON
 * scheduling-enabled     run the period-close job and the outbox relay on this instance       env BILLING_SCHEDULING_ENABLED
 * outbox.relay-interval  delay between relay runs, ISO-8601 (default PT2S)                    env BILLING_OUTBOX_RELAY_INTERVAL
 * outbox.batch-size      outbox rows claimed per relay run (default 100)                      env BILLING_OUTBOX_BATCH_SIZE
 * outbox.send-timeout    max wait for the broker ack of one record (default PT15S)            env BILLING_OUTBOX_SEND_TIMEOUT
 * </pre>
 *
 * <p>The cron and relay interval are read by {@code @Scheduled} placeholders; they are bound here too so a
 * malformed value fails at startup instead of silently disabling a job.
 */
@Validated
@ConfigurationProperties("meterly.billing")
public record BillingProperties(
        @NotBlank @DefaultValue("0 0 2 1 * *") String closeCron,
        @DefaultValue("true") boolean schedulingEnabled,
        @Valid @DefaultValue Outbox outbox) {

    public BillingProperties {
        if (closeCron != null && !CronExpression.isValidExpression(closeCron)) {
            throw new IllegalArgumentException("meterly.billing.close-cron is not a valid cron expression: " + closeCron);
        }
    }

    public record Outbox(
            @NotNull @DefaultValue("PT2S") Duration relayInterval,
            @Min(1) @Max(1000) @DefaultValue("100") int batchSize,
            @NotNull @DefaultValue("PT15S") Duration sendTimeout) {
    }
}
