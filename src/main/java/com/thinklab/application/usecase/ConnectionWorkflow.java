package com.thinklab.application.usecase;

import com.thinklab.domain.exception.ConnectionNotFoundException;
import com.thinklab.domain.model.Connection;
import com.thinklab.domain.model.Connection.ConnectionAuditEntry;
import com.thinklab.domain.repository.ConnectionRepository;
import jakarta.inject.Singleton;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.function.Function;

/** The shared shape of every staff action on a connection: refuse a REQUESTER, load it inside the tenant, apply the domain behavior, save it as a guarded write (ADR-033). */
@Singleton
public class ConnectionWorkflow {

    private final ConnectionRepository connectionRepository;

    public ConnectionWorkflow(ConnectionRepository connectionRepository) {
        this.connectionRepository = connectionRepository;
    }

    public Mono<Void> apply(UUID id, UUID organisationId, String role, String operation, Function<Connection, ConnectionAuditEntry> action) {
        return Mono.fromRunnable(() -> IntegrationAccess.requireStaff(role, operation))
                .then(Mono.defer(() -> connectionRepository.findById(id, organisationId)))
                .switchIfEmpty(Mono.error(new ConnectionNotFoundException("Connection " + id + " not found.")))
                .flatMap(connection -> {
                    var statusBefore = connection.getStatus();
                    ConnectionAuditEntry entry = action.apply(connection);
                    return connectionRepository.save(connection, statusBefore, entry);
                });
    }
}
