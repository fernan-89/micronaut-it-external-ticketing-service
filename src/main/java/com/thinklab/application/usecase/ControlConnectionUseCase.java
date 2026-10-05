package com.thinklab.application.usecase;

import com.thinklab.domain.model.Connection;
import com.thinklab.domain.model.Connection.ConnectionAuditEntry;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Use Case for switching a Connection on and off (BIAN Behavior Qualifier: {@code connection/control/*}). */
@Singleton
public class ControlConnectionUseCase {

    private static final Logger log = LoggerFactory.getLogger(ControlConnectionUseCase.class);

    /** What a person can ask for; each constant says how the aggregate performs it. */
    public enum Action {
        ENABLE {
            @Override ConnectionAuditEntry apply(Connection connection, String executor) { return connection.enable(executor); }
        },
        DISABLE {
            @Override ConnectionAuditEntry apply(Connection connection, String executor) { return connection.disable(executor); }
        };

        abstract ConnectionAuditEntry apply(Connection connection, String executor);
    }

    private final ConnectionWorkflow workflow;

    public ControlConnectionUseCase(ConnectionWorkflow workflow) {
        this.workflow = workflow;
    }

    public Mono<Void> execute(UUID id, UUID organisationId, Action action, String executor, String role) {
        log.info("[USE CASE] {} on Connection ID: {}", action, id);

        return workflow.apply(id, organisationId, role, "switch an integration", connection -> action.apply(connection, executor));
    }
}
