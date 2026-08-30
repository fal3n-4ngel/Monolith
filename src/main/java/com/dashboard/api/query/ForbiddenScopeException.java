package com.dashboard.api.query;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * A credential asked to read an app it is not entitled to — either a different app than the one
 * it is bound to, or any app at all when it has no read access. Maps to HTTP 403.
 */
public class ForbiddenScopeException extends ResponseStatusException {

    public ForbiddenScopeException(String reason) {
        super(HttpStatus.FORBIDDEN, reason);
    }
}
