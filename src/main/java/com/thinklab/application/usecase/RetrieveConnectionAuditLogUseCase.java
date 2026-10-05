package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.AuditEntryResponse;
import com.thinklab.application.mapper.ConnectionMapper;
import com.thinklab.domain.exception.ConnectionNotFoundException;
import com.thinklab.domain.repository.ConnectionRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** Use Case for the forensic ledger of a Connection (BIAN Behavior Qualifier: {@code connection/audit-log/retrieve}). Staff only. */
@Singleton
public class RetrieveConnectionAuditLogUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetrieveConnectionAuditLogUseCase.class);

    private final ConnectionRepository connectionRepository;

    public RetrieveConnectionAuditLogUseCase(ConnectionRepository connectionRepository) {
        this.connectionRepository = connectionRepository;
    }

    public Mono<List<AuditEntryResponse>> execute(UUID id, UUID organisationId, String role) {
        log.info("[USE CASE] Retrieving the audit log of Connection ID: {}", id);

        return Mono.fromRunnable(() -> IntegrationAccess.requireStaff(role, "read the audit trail of an integration"))
                .then(Mono.defer(() -> connectionRepository.findById(id, organisationId)))
                .switchIfEmpty(Mono.error(new ConnectionNotFoundException("Connection " + id + " not found.")))
                .map(connection -> connection.getAuditTrail().stream().map(ConnectionMapper::toResponse).collect(Collectors.toList()));
    }
}
