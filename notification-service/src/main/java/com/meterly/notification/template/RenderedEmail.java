package com.meterly.notification.template;

import java.util.Objects;

/**
 * Subject and body rendered for one event, identical for every recipient of that event.
 *
 * @param type the event's wire discriminator ({@code invoice.issued}, {@code usage.threshold_crossed}),
 *             stored as {@code notification.type}
 */
public record RenderedEmail(String type, String subject, String body) {

    public RenderedEmail {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(body, "body");
    }
}
