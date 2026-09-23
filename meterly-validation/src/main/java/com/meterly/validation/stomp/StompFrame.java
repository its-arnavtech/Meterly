package com.meterly.validation.stomp;

import java.util.Map;

/** One STOMP 1.2 frame: a command, headers, and an optional body. */
public record StompFrame(String command, Map<String, String> headers, String body) {

    public static final String CONNECT = "CONNECT";
    public static final String CONNECTED = "CONNECTED";
    public static final String SUBSCRIBE = "SUBSCRIBE";
    public static final String MESSAGE = "MESSAGE";
    public static final String ERROR = "ERROR";
    public static final String DISCONNECT = "DISCONNECT";

    public String header(String name) {
        return headers.get(name);
    }
}
