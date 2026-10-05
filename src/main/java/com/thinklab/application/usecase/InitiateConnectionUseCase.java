package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.InitiateConnectionRequest;
import com.thinklab.application.dto.response.ConnectionResponse;
import com.thinklab.application.mapper.ConnectionMapper;
import com.thinklab.domain.exception.DuplicateConnectionException;
import com.thinklab.domain.model.Connection;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.port.SecretResolverPort;
import com.thinklab.domain.repository.ConnectionRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Use Case for configuring a Connection (BIAN Behavior Qualifier: {@code connection/initiate}). Staff only; the address passes the policy; the name is unique in the organisation. */
@Singleton
public class InitiateConnectionUseCase {

    private static final Logger log = LoggerFactory.getLogger(InitiateConnectionUseCase.class);

    private final HashServicePort hashServicePort;
    private final ConnectionRepository connectionRepository;
    private final BaseUrlPolicy baseUrlPolicy;
    private final SecretResolverPort secrets;

    public InitiateConnectionUseCase(HashServicePort hashServicePort, ConnectionRepository connectionRepository, BaseUrlPolicy baseUrlPolicy, SecretResolverPort secrets) {
        this.hashServicePort = hashServicePort;
        this.connectionRepository = connectionRepository;
        this.baseUrlPolicy = baseUrlPolicy;
        this.secrets = secrets;
    }

    public Mono<ConnectionResponse> execute(UUID organisationId, InitiateConnectionRequest request, String executor, String role) {
        log.info("[USE CASE] Configuring a Connection [{}] to {} for organisation: {}", request.name(), request.provider(), organisationId);

        return Mono.fromRunnable(() -> {
                    IntegrationAccess.requireStaff(role, "configure an integration");
                    baseUrlPolicy.check(request.baseUrl());
                })
                .then(Mono.defer(() -> connectionRepository.existsByName(request.name(), organisationId)))
                .flatMap(taken -> taken
                        ? Mono.<UUID>error(new DuplicateConnectionException("A connection named [" + request.name() + "] already exists."))
                        : hashServicePort.generateSovereignId("connection-creation"))
                .map(sovereignId -> ConnectionMapper.toDomain(request, sovereignId, organisationId, executor))
                .flatMap(connectionRepository::create)
                .map(connection -> toResponse(connection));
    }

    private ConnectionResponse toResponse(Connection connection) {
        return ConnectionMapper.toResponse(connection, secrets.resolve(connection.getSecretRef()) != null, secrets.resolve(connection.getWebhookSecretRef()) != null);
    }
}
