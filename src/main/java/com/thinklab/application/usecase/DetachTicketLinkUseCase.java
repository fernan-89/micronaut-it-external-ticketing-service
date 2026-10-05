package com.thinklab.application.usecase;

import com.thinklab.domain.exception.TicketLinkNotFoundException;
import com.thinklab.domain.repository.TicketLinkRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Use Case for dropping a pairing (BIAN Behavior Qualifier: {@code control/detach}): terminal, and the ticket at the provider is left as it is. */
@Singleton
public class DetachTicketLinkUseCase {

    private static final Logger log = LoggerFactory.getLogger(DetachTicketLinkUseCase.class);

    private final TicketLinkRepository linkRepository;

    public DetachTicketLinkUseCase(TicketLinkRepository linkRepository) {
        this.linkRepository = linkRepository;
    }

    public Mono<Void> execute(UUID id, UUID organisationId, String executor, String role) {
        log.info("[USE CASE] Detaching TicketLink ID: {}", id);

        return Mono.fromRunnable(() -> IntegrationAccess.requireStaff(role, "detach a ticket"))
                .then(Mono.defer(() -> linkRepository.findById(id, organisationId)))
                .switchIfEmpty(Mono.error(new TicketLinkNotFoundException("TicketLink " + id + " not found.")))
                .flatMap(link -> {
                    var statusBefore = link.getStatus();
                    var entry = link.detach(executor);
                    return linkRepository.save(link, statusBefore, entry);
                });
    }
}
