package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.TicketLinkResponse;
import com.thinklab.application.mapper.TicketLinkMapper;
import com.thinklab.domain.exception.ConnectionNotFoundException;
import com.thinklab.domain.exception.InvalidTicketLinkStatusException;
import com.thinklab.domain.exception.TicketLinkNotFoundException;
import com.thinklab.domain.model.TicketLink.LinkStatus;
import com.thinklab.domain.repository.ConnectionRepository;
import com.thinklab.domain.repository.TicketLinkRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Use Case for "sync now" (BIAN Behavior Qualifier: {@code sync/execute}): retries a ticket that was never created, or pushes the status and the public comments that changed. A detached link is not synced. */
@Singleton
public class SyncTicketLinkUseCase {

    private static final Logger log = LoggerFactory.getLogger(SyncTicketLinkUseCase.class);

    private final TicketLinkRepository linkRepository;
    private final ConnectionRepository connectionRepository;
    private final TicketSyncService syncService;

    public SyncTicketLinkUseCase(TicketLinkRepository linkRepository, ConnectionRepository connectionRepository, TicketSyncService syncService) {
        this.linkRepository = linkRepository;
        this.connectionRepository = connectionRepository;
        this.syncService = syncService;
    }

    public Mono<TicketLinkResponse> execute(UUID id, UUID organisationId, String executor, String role) {
        log.info("[USE CASE] Syncing TicketLink ID: {}", id);

        return Mono.fromRunnable(() -> IntegrationAccess.requireStaff(role, "sync a ticket"))
                .then(Mono.defer(() -> linkRepository.findById(id, organisationId)))
                .switchIfEmpty(Mono.error(new TicketLinkNotFoundException("TicketLink " + id + " not found.")))
                .flatMap(link -> {
                    if (link.getStatus() == LinkStatus.DETACHED) {
                        return Mono.error(new InvalidTicketLinkStatusException("Illegal transition: a DETACHED TicketLink is no longer synced."));
                    }
                    return connectionRepository.findById(link.getConnectionId(), organisationId)
                            .switchIfEmpty(Mono.error(new ConnectionNotFoundException("Connection " + link.getConnectionId() + " not found.")))
                            .flatMap(connection -> syncService.sync(link, connection, executor));
                })
                .map(TicketLinkMapper::toResponse);
    }
}
