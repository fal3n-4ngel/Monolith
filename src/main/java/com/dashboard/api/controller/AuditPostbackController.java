package com.dashboard.api.controller;

import com.dashboard.api.dto.AuditPostbackDto;
import com.dashboard.api.dto.AuditPostbackResponse;
import com.dashboard.api.service.AuditPostbackService;
import com.dashboard.api.service.AuditPostbackService.RequestContext;
import com.dashboard.api.web.ClientIpResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Tag(name = "Audit & Telemetry", description = "Central audit log receiver for Continuum and integrated personal applications")
@RestController
@RequestMapping({"/api/v1/audit", "/audit", "/api/audit"})
public class AuditPostbackController {

    private final AuditPostbackService auditService;

    public AuditPostbackController(AuditPostbackService auditService) {
        this.auditService = auditService;
    }

    @Operation(
            summary = "Record an inbound audit postback",
            description = """
                    Accepts telemetry from Continuum Home, mobile clients, CLI tools, and Custom GPT actions.

                    Unauthenticated by design so browser clients can post without shipping a usable
                    credential. Responses are `202 Accepted`: the event is validated and queued, then
                    persisted off the request thread. Callers must not treat the response as a
                    durability guarantee. Subject to per-IP rate limiting.""")
    @SecurityRequirements
    @PostMapping("/postback")
    public ResponseEntity<AuditPostbackResponse> recordPostback(
            @Valid @RequestBody AuditPostbackDto payload,
            HttpServletRequest request) {

        RequestContext context = new RequestContext(
                request.getHeader("Origin"),
                request.getHeader("Referer"),
                request.getHeader("User-Agent"),
                ClientIpResolver.resolve(request));

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(auditService.record(payload, context));
    }

    @Operation(
            summary = "Query centralized audit logs",
            description = "Retrieve historical audit events filtered by source application, event type, and severity.",
            security = @SecurityRequirement(name = "BearerAuth"))
    @GetMapping("/logs")
    public ResponseEntity<List<Map<String, Object>>> getAuditLogs(
            @Parameter(description = "Filter by source application (e.g. continuum-home)")
            @RequestParam(required = false) String sourceApp,
            @Parameter(description = "Filter by event name (e.g. USER_SESSION_ACTIVE)")
            @RequestParam(required = false) String eventType,
            @Parameter(description = "Filter by severity (DEBUG, INFO, WARN, ERROR, CRITICAL)")
            @RequestParam(required = false) String severity,
            @Parameter(description = "Max records to return; clamped to audit.max-query-limit")
            @RequestParam(required = false) Integer limit) {

        return ResponseEntity.ok(auditService.queryAuditLogs(sourceApp, eventType, severity, limit));
    }
}
