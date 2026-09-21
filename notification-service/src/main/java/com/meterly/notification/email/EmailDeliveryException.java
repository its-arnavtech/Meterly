package com.meterly.notification.email;

/** The email provider did not accept a message. Recorded on the notification row; retried later. */
public class EmailDeliveryException extends RuntimeException {

    public EmailDeliveryException(String message) {
        super(message);
    }

    public EmailDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
