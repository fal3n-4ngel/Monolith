package com.dashboard.api.dto;

import com.dashboard.api.events.DomainEventType;
import com.dashboard.api.events.SourceApp;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;

/**
 * The validated shape of a {@code GET /audit/logs} request.
 *
 * <p>Every filter is checked against the same allowlists the ingest path uses ({@link SourceApp},
 * {@link DomainEventType}), so an unknown app, domain, or event type is a {@code 400} here rather
 * than a query that quietly matches nothing. Nothing in here decides <i>scope</i> — that comes
 * from the authenticated credential in {@code AuditLogService}.
 */
public record AuditLogQuery(
        Optional<SourceApp> sourceApp,
        String userId,
        String domain,
        DomainEventType eventType,
        Instant from,
        Instant before,
        Integer limit
) {

    public static AuditLogQuery parse(String sourceApp, String userId, String domain, String eventType,
                                      String from, String before, Integer limit) {
        Optional<SourceApp> app = Optional.empty();
        if (hasText(sourceApp)) {
            app = SourceApp.parse(sourceApp);
            if (app.isEmpty()) {
                throw badRequest("unknown sourceApp: " + sourceApp);
            }
        }

        String domainClean = null;
        if (hasText(domain)) {
            domainClean = domain.trim().toLowerCase(Locale.ROOT);
            if (!DomainEventType.domains().contains(domainClean)) {
                throw badRequest("unknown domain: " + domain);
            }
        }

        DomainEventType type = null;
        if (hasText(eventType)) {
            type = DomainEventType.parse(eventType)
                    .orElseThrow(() -> badRequest("unknown eventType: " + eventType));
        }

        Instant fromTs = parseInstant(from, "from");
        Instant beforeTs = parseInstant(before, "before");

        return new AuditLogQuery(app, hasText(userId) ? userId.trim() : null, domainClean, type, fromTs, beforeTs, limit);
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
