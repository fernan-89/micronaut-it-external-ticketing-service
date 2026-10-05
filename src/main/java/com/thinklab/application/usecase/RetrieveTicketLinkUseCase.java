package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.TicketLinkResponse;
import com.thinklab.application.mapper.TicketLinkMapper;
import com.thinklab.domain.exception.TicketLinkNotFoundException;
import com.thinklab.domain.repository.TicketLinkRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Use Case for reading one TicketLink of the tenant (BIAN Behavior Qualifier: {@code retrieve}). Staff only. */
@Singleton
public class RetrieveTicketLinkUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetrieveTicketLinkUseCase.class);

    private final TicketLinkRepository linkRepository;

    public RetrieveTicketLinkUseCase(TicketLinkRepository linkRepository) {
        this.linkRepository = linkRepository;
    }

    public Mono<TicketLinkResponse> execute(UUID id, UUID organisationId, String role) {
        log.info("[USE CASE] Retrieving TicketLink by ID: {}", id);

        return Mono.fromRunnable(() -> IntegrationAccess.requireStaff(role, "read a ticket link"))
                .then(Mono.defer(() -> linkRepository.findById(id, organisationId)))
                .switchIfEmpty(Mono.error(new TicketLinkNotFoundException("TicketLink " + id + " not found.")))
                .map(TicketLinkMapper::toResponse);
    }
}
