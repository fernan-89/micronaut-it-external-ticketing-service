package com.thinklab.domain.repository;

import com.thinklab.domain.model.TicketLink;
import com.thinklab.domain.model.TicketLink.LinkAuditEntry;
import com.thinklab.domain.model.TicketLink.LinkStatus;
import com.thinklab.domain.model.TicketLink.SubjectType;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Outbound Port for TicketLink persistence (IT External Ticketing Service Domain). {@link #save} is a guarded write (ADR-033). Every
 * lookup is tenant-scoped.
 */
public interface TicketLinkRepository {

    /**
     * Inserts the link. A second link of the same item to the same connection is
     * {@link com.thinklab.domain.exception.DuplicateTicketLinkException} (the unique index is the atomic backstop).
     */
    Mono<TicketLink> create(TicketLink link);

    Mono<TicketLink> findById(UUID id, UUID organisationId);

    /** The link a provider ticket belongs to, for an inbound event. */
    Mono<TicketLink> findByExternalId(UUID connectionId, String externalId, UUID organisationId);

    Flux<TicketLink> findAll(UUID organisationId, Filter filter);

    Mono<Void> save(TicketLink link, LinkStatus expectedStatus, LinkAuditEntry auditEntry);

    /** Optional filters of the collection. */
    record Filter(UUID connectionId, SubjectType subjectType, UUID subjectId, LinkStatus status) {
    }
}
