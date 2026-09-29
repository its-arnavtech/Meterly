package com.meterly.notification.delivery;

import java.util.UUID;

/**
 * A claimed {@code notification} row this process is responsible for sending right now.
 *
 * @param attempts send attempts including the current one (1 for a fresh claim)
 */
public record PendingNotification(
        UUID id,
        UUID tenantId,
        UUID eventId,
        String type,
        String recipient,
        String subject,
        String body,
        int attempts) {
}
