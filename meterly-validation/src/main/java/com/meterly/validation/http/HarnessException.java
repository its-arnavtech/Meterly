package com.meterly.validation.http;

/** Unchecked wrapper for transport/setup failures the harness cannot recover from. */
public class HarnessException extends RuntimeException {

    public HarnessException(String message) {
        super(message);
    }

    public HarnessException(String message, Throwable cause) {
        super(message, cause);
    }
}
