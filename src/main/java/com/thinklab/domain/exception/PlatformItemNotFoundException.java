package com.thinklab.domain.exception;

/** Domain Exception: the incident, service request or problem to link does not exist in the organisation. RFC 7807 mapping: HTTP 404 Not Found. */
public class PlatformItemNotFoundException extends BusinessException {

    private static final String ERROR_CODE = "ERR-ETK-00404";

    public PlatformItemNotFoundException(String message) {
        super(ERROR_CODE, message);
    }
}
