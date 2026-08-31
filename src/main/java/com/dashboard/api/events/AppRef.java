package com.dashboard.api.events;

/**
 * A source-app id that has been checked against the registry. Only {@link AppRegistry} mints these;
 * everywhere else takes one as proof the app is registered.
 */
public record AppRef(String id) {

    public AppRef {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("app id must not be blank");
        }
    }

    /** Alias kept for call sites that predate the rename from {@code SourceApp}. */
    public String appId() {
        return id;
    }
}
