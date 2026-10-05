package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.ConnectionResponse;
import com.thinklab.application.mapper.ConnectionMapper;
import com.thinklab.domain.port.SecretResolverPort;
import com.thinklab.domain.repository.ConnectionRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Use Case for listing the tenant's Connections (BIAN Behavior Qualifier: {@code connection/retrieve}, collection). */
@Singleton
public class RetrieveConnectionsUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetrieveConnectionsUseCase.class);

    private final ConnectionRepository connectionRepository;
    private final SecretResolverPort secrets;

    public RetrieveConnectionsUseCase(ConnectionRepository connectionRepository, SecretResolverPort secrets) {
        this.connectionRepository = connectionRepository;
        this.secrets = secrets;
    }

    public Flux<ConnectionResponse> execute(UUID organisationId, String role) {
        log.info("[USE CASE] Retrieving the Connections of organisation: {}", organisationId);

        return Mono.fromRunnable(() -> IntegrationAccess.requireStaff(role, "list integrations"))
                .thenMany(Flux.defer(() -> connectionRepository.findAll(organisationId)))
                .map(connection -> ConnectionMapper.toResponse(connection, secrets.resolve(connection.getSecretRef()) != null, secrets.resolve(connection.getWebhookSecretRef()) != null));
    }
}
