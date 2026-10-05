package com.thinklab.domain.exception;

/**
 * Domain Exception: Thrown when an TicketLink is initiated with a serial number that already exists
 * within the same Organisation scope.
 *
 * <p>RFC 7807 mapping: HTTP 409 Conflict.
 */
public class DuplicateTicketLinkException extends BusinessException {

    private static final String ERROR_CODE = "ERR-ETK-00409";

    public DuplicateTicketLinkException(String message) {
        super(ERROR_CODE, message);
    }
}
