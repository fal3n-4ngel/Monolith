package com.dashboard.api.query;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** A credential asked to read an app it is not entitled to. */
public class ForbiddenScopeException extends ResponseStatusException {

    public ForbiddenScopeException(String reason) {
        super(HttpStatus.FORBIDDEN, reason);
    }
}
