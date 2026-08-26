package com.dashboard.api.events;

import java.util.Locale;
import java.util.Optional;

/**
 * Server-side allowlist of registered source applications authorized to emit domain events.
 * Unregistered sourceApp strings are rejected with HTTP 400.
 */
public enum SourceApp {

    CONTINUUM_HOME("continuum-home"),
    MONOLITH_DASHBOARD("monolith-dashboard"),
    CHAYAKUDIKANPOOYALO("chayakudikanpooyalo");

    private final String appId;

    SourceApp(String appId) {
        this.appId = appId;
    }

    public String appId() {
        return appId;
    }

    public static Optional<SourceApp> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String cleaned = raw.trim().toLowerCase(Locale.ROOT);
        for (SourceApp app : values()) {
            if (app.appId.equalsIgnoreCase(cleaned)) {
                return Optional.of(app);
            }
        }
        return Optional.empty();
    }
}
