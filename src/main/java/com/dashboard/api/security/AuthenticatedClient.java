package com.dashboard.api.security;

import com.dashboard.api.events.AppRef;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;

/**
 * The identity behind an authenticated request. {@code boundApp} present means the credential may
 * read only that app; empty with {@code crossAppRead} means every app; empty without means it
 * authenticates but has no read access.
 */
public record AuthenticatedClient(String name, boolean crossAppRead, Optional<AppRef> boundApp)
        implements Principal {

    public static AuthenticatedClient crossApp(String name) {
        return new AuthenticatedClient(name, true, Optional.empty());
    }

    public static AuthenticatedClient boundTo(String name, AppRef app) {
        return new AuthenticatedClient(name, false, Optional.of(app));
    }

    public static AuthenticatedClient noRead(String name) {
        return new AuthenticatedClient(name, false, Optional.empty());
    }

    /**
     * {@code all}/{@code *}/blank &rarr; cross-app; {@code none}/{@code off} &rarr; no read; else a
     * registered app id (checked against {@code knownAppIds}).
     */
    public static AuthenticatedClient fromScope(String name, String scopeConfig, Collection<String> knownAppIds) {
        String scope = scopeConfig == null ? "" : scopeConfig.trim().toLowerCase(Locale.ROOT);
        if (scope.isEmpty() || scope.equals("all") || scope.equals("*")) {
            return crossApp(name);
        }
        if (scope.equals("none") || scope.equals("off")) {
            return noRead(name);
        }
        if (!knownAppIds.contains(scope)) {
            throw new IllegalStateException(
                    "Invalid read scope '" + scopeConfig + "' for the '" + name + "' credential; "
                            + "expected 'all', 'none', or a registered app id");
        }
        return boundTo(name, new AppRef(scope));
    }

    /** The client on the security context, or 401 if the route somehow ran without one. */
    public static AuthenticatedClient require(Authentication authentication) {
        Object principal = authentication == null ? null : authentication.getPrincipal();
        if (principal instanceof AuthenticatedClient client) {
            return client;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unresolved client identity");
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
