package com.thinklab.domain.exception;

/** Domain Exception: the incident, request or problem refused an action that came from the provider (its own state wins, ADR-031). RFC 7807 mapping: HTTP 409 Conflict. */
public class PlatformActionRefusedException extends BusinessException {

    private static final String ERROR_CODE = "ERR-ETK-00409";

    public PlatformActionRefusedException(String message) {
        super(ERROR_CODE, message);
    }
}
