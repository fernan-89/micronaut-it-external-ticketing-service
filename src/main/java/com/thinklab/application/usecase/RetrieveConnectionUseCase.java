package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.ConnectionResponse;
import com.thinklab.application.mapper.ConnectionMapper;
import com.thinklab.domain.exception.ConnectionNotFoundException;
import com.thinklab.domain.port.SecretResolverPort;
import com.thinklab.domain.repository.ConnectionRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Use Case for reading one Connection (BIAN Behavior Qualifier: {@code connection/retrieve}). Says whether its secrets are configured here, never what they are. */
@Singleton
public class RetrieveConnectionUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetrieveConnectionUseCase.class);

    private final ConnectionRepository connectionRepository;
    private final SecretResolverPort secrets;

    public RetrieveConnectionUseCase(ConnectionRepository connectionRepository, SecretResolverPort secrets) {
        this.connectionRepository = connectionRepository;
        this.secrets = secrets;
    }

    public Mono<ConnectionResponse> execute(UUID id, UUID organisationId, String role) {
        log.info("[USE CASE] Retrieving Connection by ID: {}", id);

        return Mono.fromRunnable(() -> IntegrationAccess.requireStaff(role, "read an integration"))
                .then(Mono.defer(() -> connectionRepository.findById(id, organisationId)))
                .switchIfEmpty(Mono.error(new ConnectionNotFoundException("Connection " + id + " not found.")))
                .map(connection -> ConnectionMapper.toResponse(connection, secrets.resolve(connection.getSecretRef()) != null, secrets.resolve(connection.getWebhookSecretRef()) != null));
    }
}
