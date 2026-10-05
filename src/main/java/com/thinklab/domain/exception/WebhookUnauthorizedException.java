package com.thinklab.domain.exception;

/** Domain Exception: an inbound webhook did not present the connection secret. RFC 7807 mapping: HTTP 401 Unauthorized. */
public class WebhookUnauthorizedException extends BusinessException {

    private static final String ERROR_CODE = "ERR-ETK-00401";

    public WebhookUnauthorizedException(String message) {
        super(ERROR_CODE, message);
    }
}
