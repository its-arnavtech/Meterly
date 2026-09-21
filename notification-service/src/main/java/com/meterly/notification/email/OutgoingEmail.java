package com.meterly.notification.email;

import java.util.Objects;

/** A plain-text email to one recipient. The From address is the provider's configured sender. */
public record OutgoingEmail(String to, String subject, String body) {

    public OutgoingEmail {
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(body, "body");
    }
}
