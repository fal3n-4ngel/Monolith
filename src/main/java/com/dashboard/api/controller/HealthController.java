package com.dashboard.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "Health", description = "Liveness endpoint for Cloud Run probes and uptime checks")
@RestController
public class HealthController {

    private final String serviceName;

    public HealthController(@Value("${spring.application.name:monolith-api}") String serviceName) {
        this.serviceName = serviceName;
    }

    /**
     * Deliberately minimal. This endpoint is public and is hit by the keep-alive workflow on a
     * schedule, so it must not touch Firestore: a dependency check here would turn every ping
     * into a billed read and would fail the liveness probe on a transient backend blip.
     */
    @Operation(summary = "Service liveness", description = "Returns service identity and server time. No backend dependencies are checked.")
    @SecurityRequirements
    @GetMapping({"/", "/health"})
    public Map<String, Object> healthCheck() {
        return Map.of(
                "status", "UP",
                "service", serviceName,
                "timestamp", System.currentTimeMillis());
    }
}
