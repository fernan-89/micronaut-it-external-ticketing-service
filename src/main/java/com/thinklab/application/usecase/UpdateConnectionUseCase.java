package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.UpdateConnectionRequest;
import com.thinklab.domain.exception.ConnectionNotFoundException;
import com.thinklab.domain.exception.DuplicateConnectionException;
import com.thinklab.domain.repository.ConnectionRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Use Case for changing a Connection (BIAN Behavior Qualifier: {@code connection/update}): the address passes the policy again and a new name must still be unique. */
@Singleton
public class UpdateConnectionUseCase {

    private static final Logger log = LoggerFactory.getLogger(UpdateConnectionUseCase.class);

    private final ConnectionRepository connectionRepository;
    private final BaseUrlPolicy baseUrlPolicy;

    public UpdateConnectionUseCase(ConnectionRepository connectionRepository, BaseUrlPolicy baseUrlPolicy) {
        this.connectionRepository = connectionRepository;
        this.baseUrlPolicy = baseUrlPolicy;
    }

    public Mono<Void> execute(UUID id, UUID organisationId, UpdateConnectionRequest request, String executor, String role) {
        log.info("[USE CASE] Updating Connection ID: {}", id);

        return Mono.fromRunnable(() -> {
                    IntegrationAccess.requireStaff(role, "change an integration");
                    baseUrlPolicy.check(request.baseUrl());
                })
                .then(Mono.defer(() -> connectionRepository.findById(id, organisationId)))
                .switchIfEmpty(Mono.error(new ConnectionNotFoundException("Connection " + id + " not found.")))
                .flatMap(connection -> {
                    Mono<Boolean> taken = connection.getName().equals(request.name()) ? Mono.just(false) : connectionRepository.existsByName(request.name(), organisationId);
                    return taken.flatMap(isTaken -> {
                        if (isTaken) {
                            return Mono.<Void>error(new DuplicateConnectionException("A connection named [" + request.name() + "] already exists."));
                        }
                        var statusBefore = connection.getStatus();
                        var entry = connection.updateDetails(request.name(), request.baseUrl().trim(), request.secretRef(), request.webhookSecretRef(),
                                request.integrationActor(), request.projectKey(), request.outboundStatus(), request.inboundActions(), executor);
                        return connectionRepository.save(connection, statusBefore, entry);
                    });
                });
    }
}
