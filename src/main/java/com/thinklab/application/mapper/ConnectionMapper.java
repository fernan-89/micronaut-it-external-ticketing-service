package com.thinklab.application.mapper;

import com.thinklab.application.dto.request.InitiateConnectionRequest;
import com.thinklab.application.dto.response.AuditEntryResponse;
import com.thinklab.application.dto.response.ConnectionResponse;
import com.thinklab.domain.model.Connection;
import com.thinklab.domain.model.Connection.ConnectionAuditEntry;

import java.util.UUID;

/** Maps between the Connection aggregate and its DTOs. Static, stateless. */
public final class ConnectionMapper {

    private ConnectionMapper() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    public static Connection toDomain(InitiateConnectionRequest request, UUID sovereignId, UUID organisationId, String executor) {
        return Connection.createNew(sovereignId, organisationId, request.name(), request.provider(), request.baseUrl().trim(), request.secretRef(),
                request.webhookSecretRef(), request.integrationActor(), request.projectKey(), request.outboundStatus(), request.inboundActions(), executor);
    }

    public static ConnectionResponse toResponse(Connection connection, boolean secretConfigured, boolean webhookSecretConfigured) {
        return new ConnectionResponse(connection.getId(), connection.getOrganisationId(), connection.getName(), connection.getProvider().name(),
                connection.getBaseUrl(), connection.getSecretRef(), secretConfigured, connection.getWebhookSecretRef(), webhookSecretConfigured,
                connection.getIntegrationActor(), connection.getProjectKey(), connection.getOutboundStatus(), connection.getInboundActions(),
                connection.getStatus().name(), connection.getCreatedAt(), connection.getUpdatedAt());
    }

    public static AuditEntryResponse toResponse(ConnectionAuditEntry entry) {
        return new AuditEntryResponse(entry.occurredAt(), entry.action(), entry.executor(),
                entry.fromStatus() != null ? entry.fromStatus().name() : null, entry.toStatus().name(), entry.detail());
    }
}
