package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.AuditEntryResponse;
import com.thinklab.application.mapper.TicketLinkMapper;
import com.thinklab.domain.exception.TicketLinkNotFoundException;
import com.thinklab.domain.repository.TicketLinkRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** Use Case for the forensic ledger of a TicketLink (BIAN Behavior Qualifier: {@code audit-log/retrieve}): every exchange with the provider, including the refused ones. Staff only. */
@Singleton
public class RetrieveTicketLinkAuditLogUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetrieveTicketLinkAuditLogUseCase.class);

    private final TicketLinkRepository linkRepository;

    public RetrieveTicketLinkAuditLogUseCase(TicketLinkRepository linkRepository) {
        this.linkRepository = linkRepository;
    }

    public Mono<List<AuditEntryResponse>> execute(UUID id, UUID organisationId, String role) {
        log.info("[USE CASE] Retrieving the audit log of TicketLink ID: {}", id);

        return Mono.fromRunnable(() -> IntegrationAccess.requireStaff(role, "read the audit trail of a ticket link"))
                .then(Mono.defer(() -> linkRepository.findById(id, organisationId)))
                .switchIfEmpty(Mono.error(new TicketLinkNotFoundException("TicketLink " + id + " not found.")))
                .map(link -> link.getAuditTrail().stream().map(TicketLinkMapper::toResponse).collect(Collectors.toList()));
    }
}
