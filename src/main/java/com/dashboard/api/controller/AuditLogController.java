package com.dashboard.api.controller;

import com.dashboard.api.dto.AuditLogPage;
import com.dashboard.api.dto.AuditLogQuery;
import com.dashboard.api.events.AppRegistry;
import com.dashboard.api.query.AuditLogService;
import com.dashboard.api.security.AuthenticatedClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Audit Log", description = "Read access to domain-event history, scoped per credential")
@RestController
@RequestMapping({"/api/v1/audit", "/audit"})
public class AuditLogController {

    private final AuditLogService service;
    private final AppRegistry appRegistry;

    public AuditLogController(AuditLogService service, AppRegistry appRegistry) {
        this.service = service;
        this.appRegistry = appRegistry;
    }

    @Operation(summary = "Query domain-event history, newest first, scoped to the calling credential's app",
            security = @SecurityRequirement(name = "BearerAuth"))
    @GetMapping("/logs")
    public AuditLogPage logs(
            @RequestParam(required = false) String sourceApp,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String domain,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String before,
            @RequestParam(required = false) Integer limit,
            Authentication authentication) {

        AuditLogQuery request = AuditLogQuery.parse(appRegistry, sourceApp, userId, domain, eventType, from, before, limit);
        return service.query(AuthenticatedClient.require(authentication), request);
    }
}
