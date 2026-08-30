package com.dashboard.api.events;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The allowlist of domain events this service accepts, and the only place that decides which
 * table each one lands in.
 *
 * <p>Callers name an <i>event</i>, never a table — table identifiers can't be parameterized the
 * way values can, so a caller-supplied destination would be a validation problem, and schema
 * ownership belongs here, not with whoever calls the endpoint. An unrecognized {@code eventType}
 * is rejected with 400, not silently dropped.
 *
 * <p>Adding an event is a one-line change here plus a table in {@code infra/setup-bigquery.sh}.
 */
public enum DomainEventType {

    EXPENSE_CREATED("expenses", Action.CREATE),
    EXPENSE_UPDATED("expenses", Action.UPDATE),
    EXPENSE_DELETED("expenses", Action.DELETE),
    SALARY_UPDATED("expenses", Action.UPDATE),
    SALARY_LOGGED("expenses", Action.CREATE),

    WATCHLIST_ADDED("watchlist", Action.CREATE),
    WATCHLIST_UPDATED("watchlist", Action.UPDATE),
    WATCHLIST_REMOVED("watchlist", Action.DELETE),

    INVESTMENT_CREATED("investments", Action.CREATE),
    INVESTMENT_UPDATED("investments", Action.UPDATE),
    INVESTMENT_DELETED("investments", Action.DELETE),

    SUBSCRIPTION_CREATED("subscriptions", Action.CREATE),
    SUBSCRIPTION_UPDATED("subscriptions", Action.UPDATE),
    SUBSCRIPTION_DELETED("subscriptions", Action.DELETE),

    USER_DELETED("account", Action.DELETE),

    // monolith-dashboard's own usage telemetry.
    REPORT_RUN("usage", Action.CREATE),
    MCP_QUERY("usage", Action.CREATE),
    WORKSPACE_SIGNIN("usage", Action.CREATE);

    public enum Action { CREATE, UPDATE, DELETE }

    private final String domain;
    private final Action action;

    DomainEventType(String domain, Action action) {
        this.domain = domain;
        this.action = action;
    }

    /** The domain half of the table name — see {@link #tableFor(String)}. */
    public String domain() {
        return domain;
    }

    public Action action() {
        return action;
    }

    /** Distinct domain names — the allowlist for the {@code ?domain=} read filter. */
    public static Set<String> domains() {
        Set<String> all = new LinkedHashSet<>();
        for (DomainEventType type : values()) {
            all.add(type.domain);
        }
        return all;
    }

    public static Optional<DomainEventType> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** {@code {sourceApp}_{domain}} — e.g. {@code EXPENSE_CREATED} from {@code continuum-home} lands in {@code continuum_home_expenses}. */
    public String tableFor(String sourceApp) {
        return normalize(sourceApp) + "_" + domain;
    }

    /** {@code DomainEventDto} already bounds sourceApp to [A-Za-z0-9._-]; this maps it to a legal table id. */
    private static String normalize(String sourceApp) {
        return sourceApp.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
    }
}
