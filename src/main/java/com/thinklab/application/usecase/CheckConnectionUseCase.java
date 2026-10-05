package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.ConnectionCheckResponse;
import com.thinklab.domain.exception.ConnectionNotFoundException;
import com.thinklab.domain.exception.ExternalTicketingException;
import com.thinklab.domain.port.SecretResolverPort;
import com.thinklab.domain.repository.ConnectionRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Use Case that answers "does this connection work" (BIAN Behavior Qualifier: {@code connection/check/execute}): are both secrets set in
 * this environment, and does the provider accept an authenticated call. It never fails because the provider does: the answer says so.
 */
@Singleton
public class CheckConnectionUseCase {

    private static final Logger log = LoggerFactory.getLogger(CheckConnectionUseCase.class);

    private final ConnectionRepository connectionRepository;
    private final SecretResolverPort secrets;
    private final ProviderRegistry providers;

    public CheckConnectionUseCase(ConnectionRepository connectionRepository, SecretResolverPort secrets, ProviderRegistry providers) {
        this.connectionRepository = connectionRepository;
        this.secrets = secrets;
        this.providers = providers;
    }

    public Mono<ConnectionCheckResponse> execute(UUID id, UUID organisationId, String role) {
        log.info("[USE CASE] Checking Connection ID: {}", id);

        return Mono.fromRunnable(() -> IntegrationAccess.requireStaff(role, "check an integration"))
                .then(Mono.defer(() -> connectionRepository.findById(id, organisationId)))
                .switchIfEmpty(Mono.error(new ConnectionNotFoundException("Connection " + id + " not found.")))
                .flatMap(connection -> {
                    String authorization = secrets.resolve(connection.getSecretRef());
                    boolean webhookConfigured = secrets.resolve(connection.getWebhookSecretRef()) != null;
                    if (authorization == null) {
                        return Mono.just(new ConnectionCheckResponse(false, webhookConfigured, false, "The variable " + connection.getSecretRef() + " is not set in this environment."));
                    }
                    return providers.of(connection.getProvider()).ping(connection, authorization)
                            .thenReturn(new ConnectionCheckResponse(true, webhookConfigured, true, null))
                            .onErrorResume(ExternalTicketingException.class, failure -> Mono.just(new ConnectionCheckResponse(true, webhookConfigured, false, failure.getMessage())));
                });
    }
}
