package com.thinklab.domain.exception;

/** Domain Exception: a connection is DISABLED, not ready (its secret is not configured) or the change lost a race. RFC 7807 mapping: HTTP 409 Conflict. */
public class InvalidConnectionStatusException extends BusinessException {

    private static final String ERROR_CODE = "ERR-ETK-00409";

    public InvalidConnectionStatusException(String message) {
        super(ERROR_CODE, message);
    }
}
