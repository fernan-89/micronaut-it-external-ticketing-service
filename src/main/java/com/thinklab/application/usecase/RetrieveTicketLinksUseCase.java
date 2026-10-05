package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.TicketLinkResponse;
import com.thinklab.application.mapper.TicketLinkMapper;
import com.thinklab.domain.repository.TicketLinkRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Use Case for listing the tenant's TicketLinks, filterable (BIAN Behavior Qualifier: {@code retrieve}, collection). {@code subjectId} answers "is this item linked, and where". */
@Singleton
public class RetrieveTicketLinksUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetrieveTicketLinksUseCase.class);

    private final TicketLinkRepository linkRepository;

    public RetrieveTicketLinksUseCase(TicketLinkRepository linkRepository) {
        this.linkRepository = linkRepository;
    }

    public Flux<TicketLinkResponse> execute(UUID organisationId, TicketLinkRepository.Filter filter, String role) {
        log.info("[USE CASE] Retrieving TicketLinks for organisation: {}", organisationId);

        return Mono.fromRunnable(() -> IntegrationAccess.requireStaff(role, "list ticket links"))
                .thenMany(Flux.defer(() -> linkRepository.findAll(organisationId, filter)))
                .map(TicketLinkMapper::toResponse);
    }
}
