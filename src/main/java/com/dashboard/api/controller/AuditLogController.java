package com.dashboard.api.controller;

import com.dashboard.api.dto.AuditLogPage;
import com.dashboard.api.dto.AuditLogQuery;
import com.dashboard.api.query.AuditLogService;
import com.dashboard.api.security.AuthenticatedClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@Tag(name = "Audit Log", description = "Read access to domain-event history, scoped per credential")
@RestController
@RequestMapping({"/api/v1/audit", "/audit"})
public class AuditLogController {

    private final AuditLogService service;

    public AuditLogController(AuditLogService service) {
        this.service = service;
    }

    @Operation(
            summary = "Query domain-event history",
            description = """
                    Returns domain events newest-first.

                    **Scoped per credential.** A client's bearer key is bound to its own source
                    app and can read only that app's events — `sourceApp` is taken from the
                    credential, and asking for a different one is a `403`. The owner `API_KEY` and
                    an allow-listed Google identity read across every app.

                    With no `from`, only the last `audit.query-lookback-days` days are scanned, to
                    bound cost. Page by passing the previous response's `nextBefore` as `before`.""",
            security = @SecurityRequirement(name = "BearerAuth"))
    @GetMapping("/logs")
    public AuditLogPage logs(
            @Parameter(description = "Restrict to one app. Cross-app credentials only; a scoped credential must omit this or pass its own app.")
            @RequestParam(required = false) String sourceApp,
            @Parameter(description = "Filter to one acting user, as identified by the source app.")
            @RequestParam(required = false) String userId,
            @Parameter(description = "Filter to one domain: expenses, watchlist, investments, subscriptions.")
            @RequestParam(required = false) String domain,
            @Parameter(description = "Filter to one allowlisted event type, e.g. EXPENSE_CREATED.")
            @RequestParam(required = false) String eventType,
            @Parameter(description = "Lower bound on occurred_at, inclusive. ISO-8601 or epoch milliseconds.")
            @RequestParam(required = false) String from,
            @Parameter(description = "Upper bound on occurred_at, exclusive. Pass a previous nextBefore to paginate.")
            @RequestParam(required = false) String before,
            @Parameter(description = "Row cap. Defaults to audit.query-default-limit; capped at audit.query-max-limit.")
            @RequestParam(required = false) Integer limit,
            Authentication authentication) {

        AuditLogQuery request = AuditLogQuery.parse(sourceApp, userId, domain, eventType, from, before, limit);
        return service.query(principal(authentication), request);
    }

    private static AuthenticatedClient principal(Authentication authentication) {
        Object principal = authentication == null ? null : authentication.getPrincipal();
        if (principal instanceof AuthenticatedClient client) {
            return client;
        }
        // SecurityConfig requires authentication for this route; if we somehow arrive without a
        // resolved client, fail closed rather than defaulting to any access.
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unresolved client identity");
    }
}
