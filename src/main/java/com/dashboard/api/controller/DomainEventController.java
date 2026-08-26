package com.dashboard.api.controller;

import com.dashboard.api.dto.DomainEventDto;
import com.dashboard.api.dto.DomainEventResponse;
import com.dashboard.api.service.DomainEventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Domain Events", description = "Application-history events from integrated personal applications")
@RestController
@RequestMapping({"/api/v1/events", "/events"})
public class DomainEventController {

    private final DomainEventService service;

    public DomainEventController(DomainEventService service) {
        this.service = service;
    }

    @Operation(
            summary = "Record a domain event",
            description = """
                    Records an application-history event — an expense created, a watchlist item removed.

                    **Authenticated, unlike `/audit/postback`.** Domain events are emitted server-to-server
                    by a source app's own backend, never by a browser, so this endpoint can require a
                    bearer key — and does. That is what makes the resulting tables trustworthy: nobody
                    can inject a fabricated expense record by calling the public URL.

                    The destination table is resolved server-side from `eventType`; callers never name a
                    table. An `eventType` outside the allowlist returns `400 REJECTED` and stores nothing.

                    Responses are `202 Accepted`: the row is validated, routed, and queued, then written
                    off the request thread. Not a durability guarantee. Subject to per-IP rate limiting.""",
            security = @SecurityRequirement(name = "BearerAuth"))
    @PostMapping("/postback")
    public ResponseEntity<DomainEventResponse> recordEvent(@Valid @RequestBody DomainEventDto payload) {
        DomainEventResponse response = service.record(payload);

        return "REJECTED".equals(response.status())
                ? ResponseEntity.badRequest().body(response)
                : ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }
}
