package com.thinklab.domain.exception;

/** Domain Exception: the connection does not exist in the organisation. RFC 7807 mapping: HTTP 404 Not Found. */
public class ConnectionNotFoundException extends BusinessException {

    private static final String ERROR_CODE = "ERR-ETK-00404";

    public ConnectionNotFoundException(String message) {
        super(ERROR_CODE, message);
    }
}
