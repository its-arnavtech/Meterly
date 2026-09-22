package com.meterly.tenant.support;

import java.util.List;

/** The contract's list envelope: {@code { "items": [ ... ] }}. */
public record Items<T>(List<T> items) {

    public Items {
        items = List.copyOf(items);
    }

    public static <T> Items<T> of(List<T> items) {
        return new Items<>(items);
    }
}
