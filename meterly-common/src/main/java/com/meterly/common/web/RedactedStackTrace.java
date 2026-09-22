package com.meterly.common.web;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Renders an exception chain as class names and stack frames only. Exception <em>messages</em> are
 * omitted because driver and provider messages can echo SQL parameters, row contents or credentials.
 */
public final class RedactedStackTrace {

    static final int MAX_FRAMES_PER_CAUSE = 12;
    static final int MAX_CAUSES = 8;

    private RedactedStackTrace() {
    }

    public static String of(Throwable throwable) {
        StringBuilder out = new StringBuilder();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < MAX_CAUSES && seen.add(current); depth++) {
            out.append(depth == 0 ? "\n" : "\nCaused by: ").append(current.getClass().getName());
            StackTraceElement[] frames = current.getStackTrace();
            int shown = Math.min(frames.length, MAX_FRAMES_PER_CAUSE);
            for (int i = 0; i < shown; i++) {
                out.append("\n\tat ").append(frames[i]);
            }
            if (frames.length > shown) {
                out.append("\n\t... ").append(frames.length - shown).append(" more");
            }
            current = current.getCause();
        }
        return out.toString();
    }
}
