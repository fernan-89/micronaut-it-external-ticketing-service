package com.thinklab.domain.port;

import com.thinklab.domain.model.Connection.InboundAction;
import com.thinklab.domain.model.TicketLink.SubjectType;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * Outbound Port for the platform's own incidents, service requests and problems (ADR-031): read one, apply what the provider asked, add a
 * comment. The integration acts as a service identity ({@code X-Executor}), so every change it makes is attributed to it in those
 * services' audit trails. A missing item is {@link com.thinklab.domain.exception.PlatformItemNotFoundException}; one that refuses an action
 * is {@link com.thinklab.domain.exception.PlatformActionRefusedException}.
 */
public interface PlatformItemsPort {

    Mono<PlatformItem> retrieve(SubjectType type, UUID id, UUID organisationId, String executor);

    /** Performs the control route that matches the action; the actions that need a text get {@code note}. */
    Mono<Void> applyAction(SubjectType type, UUID id, UUID organisationId, InboundAction action, String note, String executor);

    Mono<Void> addComment(SubjectType type, UUID id, UUID organisationId, String text, String executor);

    /** The title and the description are what the provider ticket is created from; internal comments never leave the platform. */
    record PlatformItem(SubjectType type, UUID id, String title, String description, String status, List<PlatformComment> comments) {
    }

    record PlatformComment(UUID id, String author, String text, boolean internal) {
    }
}
