package com.thinklab.domain.port;

import com.thinklab.domain.model.Connection;
import com.thinklab.domain.model.Connection.Provider;
import com.thinklab.domain.model.TicketLink.SubjectType;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/**
 * Outbound Port for one provider, ServiceNow or Jira (ADR-030): the same five operations, whatever the provider's API looks like. A
 * failure is {@link com.thinklab.domain.exception.ExternalTicketingException}, whose message never carries the provider's response body.
 */
public interface ExternalTicketingPort {

    Provider provider();

    /** Creates the ticket and returns its id and a link to it. */
    Mono<ExternalTicket> createTicket(Connection connection, String authorization, TicketDraft draft);

    /** Moves the ticket to the given external status (for Jira through the transition that leads to it). */
    Mono<Void> updateStatus(Connection connection, String authorization, String externalId, String externalStatus);

    Mono<Void> addComment(Connection connection, String authorization, String externalId, String text);

    /** A cheap authenticated call, to prove the address and the credentials work. */
    Mono<Void> ping(Connection connection, String authorization);

    /** Reads the provider's webhook payload into the one shape the application understands; a payload that is not one is an {@link IllegalArgumentException}. */
    ExternalEvent parseWebhook(Connection connection, Map<String, Object> payload);

    /** What the provider needs to create a ticket. {@code platformRef} lets the ticket say where it came from. */
    record TicketDraft(SubjectType type, String title, String description, String externalStatus, UUID platformRef) {
    }

    record ExternalTicket(String externalId, String url) {
    }

    /** One thing that happened at the provider: a status change and/or a comment, by {@code actor}. */
    record ExternalEvent(String eventId, String externalId, String externalStatus, String commentId, String commentText, String actor) {
    }
}
