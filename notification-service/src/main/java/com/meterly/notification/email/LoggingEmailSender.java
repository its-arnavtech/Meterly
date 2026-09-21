package com.meterly.notification.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The PDD's "stubbed email provider": accepts every message and logs a redacted summary (masked
 * recipient, subject, body size) instead of delivering it. Never logs the body.
 */
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    private final String from;

    public LoggingEmailSender(String from) {
        this.from = from;
    }

    @Override
    public void send(OutgoingEmail email) {
        log.info("Stub email provider accepted message from={} to={} subject=\"{}\" body_chars={}",
                from, EmailAddresses.redact(email.to()), email.subject(), email.body().length());
    }
}
