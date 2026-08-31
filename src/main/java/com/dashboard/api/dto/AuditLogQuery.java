package com.dashboard.api.dto;

import com.dashboard.api.events.AppRef;
import com.dashboard.api.events.AppRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;

/**
 * A validated {@code GET /audit/logs} request. Filters are checked against the app registry
 * (app id, domain, event name); scope is decided elsewhere, from the credential.
 */
public record AuditLogQuery(
        Optional<AppRef> sourceApp,
        String userId,
        String domain,
        String eventType,
        Instant from,
        Instant before,
        Integer limit
) {

    public static AuditLogQuery parse(AppRegistry registry, String sourceApp, String userId, String domain,
                                      String eventType, String from, String before, Integer limit) {
        Optional<AppRef> app = Optional.empty();
        if (hasText(sourceApp)) {
            app = registry.resolveApp(sourceApp);
            if (app.isEmpty()) {
                throw badRequest("unknown sourceApp: " + sourceApp);
            }
        }

        String domainClean = null;
        if (hasText(domain)) {
            domainClean = domain.trim().toLowerCase(Locale.ROOT);
            if (!registry.domains().contains(domainClean)) {
                throw badRequest("unknown domain: " + domain);
            }
        }

        String eventName = null;
        if (hasText(eventType)) {
            eventName = registry.resolveEventAnywhere(eventType)
                    .orElseThrow(() -> badRequest("unknown eventType: " + eventType))
                    .name();
        }

        Instant fromTs = parseInstant(from, "from");
        Instant beforeTs = parseInstant(before, "before");

        return new AuditLogQuery(app, hasText(userId) ? userId.trim() : null, domainClean, eventName, fromTs, beforeTs, limit);
    }

    private static Instant parseInstant(String raw, String field) {
        if (!hasText(raw)) {
            return null;
        }
        String value = raw.trim();
        try {
            if (!value.isEmpty() && value.chars().allMatch(Character::isDigit)) {
                return Instant.ofEpochMilli(Long.parseLong(value));
            }
            return Instant.parse(value);
        } catch (DateTimeParseException | NumberFormatException e) {
            throw badRequest(field + " must be ISO-8601 (e.g. 2026-08-30T00:00:00Z) or epoch milliseconds");
        }
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
