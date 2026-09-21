package com.meterly.notification.email;

/**
 * Outbound email provider. Implementations are selected by {@code meterly.notification.email.provider}
 * ({@link LoggingEmailSender} by default, {@link SmtpEmailSender} for real SMTP).
 *
 * <p>{@link #send} either returns normally (the provider accepted the message) or throws: callers
 * record any {@link RuntimeException} as a failed attempt and retry later, so implementations should
 * wrap provider-specific failures in {@link EmailDeliveryException}.
 */
public interface EmailSender {

    void send(OutgoingEmail email);
}
