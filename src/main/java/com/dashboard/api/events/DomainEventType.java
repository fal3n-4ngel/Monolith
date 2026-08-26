package com.dashboard.api.events;

import java.util.Locale;
import java.util.Optional;

/**
 * The allowlist of domain events this service accepts, and the only place that decides which
 * table each one lands in.
 *
 * <p>Callers name an <i>event</i>, never a table. Routing is server-side on purpose: the
 * alternative — letting the client name its own destination — makes table identifiers
 * caller-controlled, and BigQuery cannot parameterize identifiers the way it parameterizes
 * values. Schema ownership stays in this repo, reviewed here, rather than with whoever happens
 * to be calling the endpoint.
 *
 * <p>Adding an event is a one-line change here plus a table in {@code infra/setup-bigquery.sh}.
 * An unrecognized {@code eventType} is rejected with 400 rather than silently dropped, so a
 * typo in a client surfaces immediately instead of becoming a hole in the record.
 */
public enum DomainEventType {

    EXPENSE_CREATED("expenses", Action.CREATE),
    EXPENSE_UPDATED("expenses", Action.UPDATE),
    EXPENSE_DELETED("expenses", Action.DELETE),

    WATCHLIST_ADDED("watchlist", Action.CREATE),
    WATCHLIST_UPDATED("watchlist", Action.UPDATE),
    WATCHLIST_REMOVED("watchlist", Action.DELETE),

    INVESTMENT_CREATED("investments", Action.CREATE),
    INVESTMENT_UPDATED("investments", Action.UPDATE),
    INVESTMENT_DELETED("investments", Action.DELETE),

    SUBSCRIPTION_CREATED("subscriptions", Action.CREATE),
    SUBSCRIPTION_UPDATED("subscriptions", Action.UPDATE),
    SUBSCRIPTION_DELETED("subscriptions", Action.DELETE);

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

    /**
     * Resolves the destination table as {@code {sourceApp}_{domain}} — e.g. a
     * {@code EXPENSE_CREATED} from {@code continuum-home} lands in {@code continuum_home_expenses}.
     *
     * <p>One table per app per domain keeps each one's rows homogeneous, while the shared column
     * set (see {@code infra/setup-bigquery.sh}) keeps every cross-app join the same shape.
     */
    public String tableFor(String sourceApp) {
        return normalize(sourceApp) + "_" + domain;
    }

    /** {@code AuditPostbackDto} already bounds sourceApp to [A-Za-z0-9._-]; this maps it to a legal table id. */
    private static String normalize(String sourceApp) {
        return sourceApp.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
    }
}
