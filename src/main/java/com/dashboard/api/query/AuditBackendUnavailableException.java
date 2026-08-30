package com.dashboard.api.query;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * BigQuery is disabled, unreachable, or errored while serving a read. Maps to HTTP 503 — the
 * request was well-formed and authorized; the backend just could not answer it right now.
 */
public class AuditBackendUnavailableException extends ResponseStatusException {

    public AuditBackendUnavailableException(String reason) {
        super(HttpStatus.SERVICE_UNAVAILABLE, reason);
    }
}
