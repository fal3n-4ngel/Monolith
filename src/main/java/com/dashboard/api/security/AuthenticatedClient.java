package com.dashboard.api.security;

import com.dashboard.api.events.SourceApp;

import java.security.Principal;
import java.util.Locale;
import java.util.Optional;

/**
 * The identity behind an authenticated request, resolved from the bearer credential by
 * {@code ApiKeyAuthFilter}.
 *
 * <p>The read path ({@code GET /audit/logs}) is scoped per credential:
 *
 * <ul>
 *   <li><b>Cross-app</b> ({@link #crossAppRead()}): may read every app's events. The owner
 *       {@code API_KEY} and an allow-listed Google identity, by default.</li>
 *   <li><b>App-bound</b> ({@link #boundApp()} present): may read only that one app, and cannot
 *       widen its scope with a request parameter.</li>
 *   <li><b>No read</b> (neither): authenticates for ingest but gets a 403 from the read path.</li>
 * </ul>
 *
 * <p>Which credential falls into which bucket is the checked-in {@code clients.json} registry,
 * not code — see {@link #fromScope(String, String)} and {@link ClientRegistry}.
 */
public record AuthenticatedClient(String name, boolean crossAppRead, Optional<SourceApp> boundApp)
        implements Principal {

    public static AuthenticatedClient crossApp(String name) {
        return new AuthenticatedClient(name, true, Optional.empty());
    }

    public static AuthenticatedClient boundTo(String name, SourceApp app) {
        return new AuthenticatedClient(name, false, Optional.of(app));
    }

    public static AuthenticatedClient noRead(String name) {
        return new AuthenticatedClient(name, false, Optional.empty());
    }

    /**
     * Builds a client from a configured scope string:
     * <ul>
     *   <li>{@code all} / {@code *} / blank &rarr; cross-app read</li>
     *   <li>{@code none} / {@code off} &rarr; no read access</li>
     *   <li>a registered {@link SourceApp} id (e.g. {@code continuum-home}) &rarr; bound to that app</li>
     * </ul>
     *
     * @throws IllegalStateException on an unrecognized value, so a typo fails the server at
     *         startup rather than silently granting or denying access.
     */
    public static AuthenticatedClient fromScope(String name, String scopeConfig) {
        String scope = scopeConfig == null ? "" : scopeConfig.trim().toLowerCase(Locale.ROOT);
        if (scope.isEmpty() || scope.equals("all") || scope.equals("*")) {
            return crossApp(name);
        }
        if (scope.equals("none") || scope.equals("off")) {
            return noRead(name);
        }
        return boundTo(name, SourceApp.parse(scope).orElseThrow(() -> new IllegalStateException(
                "Invalid read scope '" + scopeConfig + "' for the '" + name + "' credential; "
                        + "expected 'all', 'none', or a registered sourceApp id")));
    }

    public boolean isCrossApp() {
        return crossAppRead;
    }

    public boolean canRead() {
        return crossAppRead || boundApp.isPresent();
    }

    @Override
    public String getName() {
        return name;
    }
}
