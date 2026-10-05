package com.thinklab.application.usecase;

import com.thinklab.domain.exception.IntegrationAccessDeniedException;

/** The integrations are the work of IT staff (ADR-032): a REQUESTER is refused whatever they ask for. */
final class IntegrationAccess {

    static final String REQUESTER_ROLE = "REQUESTER";
    /** Every change the integration makes in the platform is attributed to {@code external-ticketing:<connection id>}, and a comment with that author is never pushed back. */
    static final String EXECUTOR_PREFIX = "external-ticketing:";

    private IntegrationAccess() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    static void requireStaff(String role, String operation) {
        if (REQUESTER_ROLE.equals(role)) {
            throw new IntegrationAccessDeniedException("A requester cannot " + operation + ": integrations are handled by IT staff.");
        }
    }
}
