package com.meterly.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RedactedStackTraceTest {

    @Test
    void includesTypesAndFramesButNeverMessages() {
        var root = new IllegalStateException("password=hunter2 row=(alice@example.com)");
        var wrapper = new RuntimeException("SQL [INSERT ... VALUES ('secret')]", root);

        String rendered = RedactedStackTrace.of(wrapper);

        assertThat(rendered)
                .contains("java.lang.RuntimeException", "Caused by: java.lang.IllegalStateException",
                        "RedactedStackTraceTest")
                .doesNotContain("hunter2", "alice@example.com", "secret");
    }

    @Test
    void survivesSelfReferencingCauseChains() {
        var a = new RuntimeException("a");
        var b = new RuntimeException("b", a);
        a.initCause(b);

        assertThat(RedactedStackTrace.of(b)).contains("Caused by: java.lang.RuntimeException");
    }
}
