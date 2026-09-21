package com.meterly.notification.event;

/**
 * A record on {@code notification-events} that can never be processed (malformed JSON, unknown
 * {@code type}, missing required fields). Classified as not retryable: the error handler sends the
 * record straight to {@code notification-events.DLT}.
 */
public class InvalidNotificationEventException extends RuntimeException {

    public InvalidNotificationEventException(String message) {
        super(message);
    }

    public InvalidNotificationEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
