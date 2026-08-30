package com.dashboard.api.query;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** BigQuery is disabled, unreachable, or errored while serving a read. */
public class AuditBackendUnavailableException extends ResponseStatusException {

    public AuditBackendUnavailableException(String reason) {
        super(HttpStatus.SERVICE_UNAVAILABLE, reason);
    }
}
