package com.thinklab.domain.exception;

/** Domain Exception: a REQUESTER tried to use the integrations (staff only, ADR-032). RFC 7807 mapping: HTTP 403 Forbidden. */
public class IntegrationAccessDeniedException extends BusinessException {

    private static final String ERROR_CODE = "ERR-ETK-00403";

    public IntegrationAccessDeniedException(String message) {
        super(ERROR_CODE, message);
    }
}
