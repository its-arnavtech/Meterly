package com.meterly.notification.delivery;

/** {@code notification.status}. {@code PENDING} = claimed, send in flight (or sender died; see retry job). */
public enum DeliveryStatus {
    PENDING,
    SENT,
    FAILED
}
