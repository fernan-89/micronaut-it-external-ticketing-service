package com.thinklab.domain.repository;

import com.thinklab.domain.model.Connection;
import com.thinklab.domain.model.Connection.ConnectionAuditEntry;
import com.thinklab.domain.model.Connection.ConnectionStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Outbound Port for Connection persistence (IT External Ticketing Service Domain).
 *
 * <p>{@link #create} is the only whole-document write; {@link #save} persists the state a domain operation reached together with its audit
 * entry in one atomic update, only while the connection is still in the status it had when it was loaded (ADR-033). Every lookup is
 * tenant-scoped except {@link #findByIdAnyTenant}, which the webhook needs (the provider cannot name the tenant; the connection id and its
 * secret identify it).
 */
public interface ConnectionRepository {

    /** Inserts the connection; a name already taken in the organisation is {@link com.thinklab.domain.exception.DuplicateConnectionException}. */
    Mono<Connection> create(Connection connection);

    Mono<Connection> findById(UUID id, UUID organisationId);

    /** For the inbound webhook only: the connection identifies the tenant. */
    Mono<Connection> findByIdAnyTenant(UUID id);

    Mono<Boolean> existsByName(String name, UUID organisationId);

    Flux<Connection> findAll(UUID organisationId);

    Mono<Void> save(Connection connection, ConnectionStatus expectedStatus, ConnectionAuditEntry auditEntry);
}
