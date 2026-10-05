package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.InitiateTicketLinkRequest;
import com.thinklab.application.dto.response.TicketLinkResponse;
import com.thinklab.application.mapper.TicketLinkMapper;
import com.thinklab.domain.exception.ConnectionNotFoundException;
import com.thinklab.domain.model.TicketLink;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.port.PlatformItemsPort;
import com.thinklab.domain.repository.ConnectionRepository;
import com.thinklab.domain.repository.TicketLinkRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Use Case for linking a platform item to a ticket at the provider (BIAN Behavior Qualifier: {@code initiate}). The item must exist, the
 * connection must be ACTIVE and the item can have only one link per connection (a unique index backs that). The link is saved PENDING
 * first, then the ticket is created (ADR-030): if the provider is down the link stays FAILED and a sync retries it, and the caller is told
 * with a 502.
 */
@Singleton
public class InitiateTicketLinkUseCase {

    private static final Logger log = LoggerFactory.getLogger(InitiateTicketLinkUseCase.class);

    private final HashServicePort hashServicePort;
    private final ConnectionRepository connectionRepository;
    private final TicketLinkRepository linkRepository;
    private final PlatformItemsPort platform;
    private final TicketSyncService syncService;

    public InitiateTicketLinkUseCase(HashServicePort hashServicePort, ConnectionRepository connectionRepository, TicketLinkRepository linkRepository,
                                     PlatformItemsPort platform, TicketSyncService syncService) {
        this.hashServicePort = hashServicePort;
        this.connectionRepository = connectionRepository;
        this.linkRepository = linkRepository;
        this.platform = platform;
        this.syncService = syncService;
    }

    public Mono<TicketLinkResponse> execute(UUID organisationId, InitiateTicketLinkRequest request, String executor, String role) {
        log.info("[USE CASE] Linking {} {} to Connection {}", request.subjectType(), request.subjectId(), request.connectionId());

        return Mono.fromRunnable(() -> IntegrationAccess.requireStaff(role, "link a ticket"))
                .then(Mono.defer(() -> connectionRepository.findById(request.connectionId(), organisationId)))
                .switchIfEmpty(Mono.error(new ConnectionNotFoundException("Connection " + request.connectionId() + " not found.")))
                .flatMap(connection -> {
                    connection.requireActive();
                    return platform.retrieve(request.subjectType(), request.subjectId(), organisationId, IntegrationAccess.EXECUTOR_PREFIX + connection.getId())
                            .then(Mono.defer(() -> hashServicePort.generateSovereignId("ticket-link-creation")))
                            .map(id -> TicketLink.createNew(id, organisationId, connection.getId(), request.subjectType(), request.subjectId(), executor))
                            .flatMap(linkRepository::create)
                            .flatMap(link -> syncService.sync(link, connection, executor));
                })
                .map(TicketLinkMapper::toResponse);
    }
}
