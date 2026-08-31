package com.dashboard.api.events;

import java.util.Locale;

/**
 * A resolved domain event from {@link AppRegistry}: its name, the domain it belongs to, and the
 * action it implies. Callers name an event; the destination table is derived here, never supplied.
 */
public record EventRef(String name, String domain, Action action) {

    /** {@code {app}_{domain}} — e.g. {@code EXPENSE_CREATED} from {@code continuum-home} → {@code continuum_home_expenses}. */
    public String tableFor(String appId) {
        return normalize(appId) + "_" + domain;
    }

    /** DomainEventDto bounds an app id to [A-Za-z0-9._-]; this maps it to a legal table identifier. */
    public static String normalize(String appId) {
        return appId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
    }
}
