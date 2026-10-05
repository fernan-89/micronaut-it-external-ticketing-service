package com.thinklab.domain.exception;

/** Domain Exception: a connection with that name already exists in the organisation. RFC 7807 mapping: HTTP 409 Conflict. */
public class DuplicateConnectionException extends BusinessException {

    private static final String ERROR_CODE = "ERR-ETK-00409";

    public DuplicateConnectionException(String message) {
        super(ERROR_CODE, message);
    }
}
