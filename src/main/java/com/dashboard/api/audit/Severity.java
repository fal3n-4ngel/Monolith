package com.dashboard.api.audit;

import java.util.Locale;

/** Severity ladder for audit events. Unknown or absent input degrades to {@link #INFO}. */
public enum Severity {
    DEBUG, INFO, WARN, ERROR, CRITICAL;

    public static Severity parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return INFO;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return INFO;
        }
    }
}
