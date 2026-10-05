package com.thinklab.domain.exception;

import java.util.Objects;
import java.util.UUID;

/**
 * Domain Exception: Indicates that a requested {@link com.thinklab.domain.model.TicketLink} could not
 * be resolved from the repository.
 *
 * <p>RFC 7807 mapping: HTTP 404 Not Found.
 */
public class TicketLinkNotFoundException extends BusinessException {

    private static final String ERROR_CODE = "ERR-ETK-00404";

    public TicketLinkNotFoundException(UUID id) {
        super(
                ERROR_CODE,
                String.format("TicketLink with sovereign ID [%s] could not be found in the system of record.",
                        Objects.requireNonNull(id, "Domain Exception constraint violated: UUID cannot be null."))
        );
    }

    public TicketLinkNotFoundException(String message) {
        super(ERROR_CODE, message);
    }
}
