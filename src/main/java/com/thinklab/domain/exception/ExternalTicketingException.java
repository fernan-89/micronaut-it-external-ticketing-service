package com.thinklab.domain.exception;

/** Domain Exception: ServiceNow or Jira failed, refused the credentials or could not be reached. The message never carries the response body (it may echo what was sent). RFC 7807 mapping: HTTP 502 Bad Gateway. */
public class ExternalTicketingException extends BusinessException {

    private static final String ERROR_CODE = "ERR-ETK-00502";

    public ExternalTicketingException(String message) {
        super(ERROR_CODE, message);
    }
}
