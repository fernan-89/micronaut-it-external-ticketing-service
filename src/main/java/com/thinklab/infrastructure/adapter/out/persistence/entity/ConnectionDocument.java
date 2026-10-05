package com.thinklab.infrastructure.adapter.out.persistence.entity;

import com.thinklab.domain.model.Connection;
import com.thinklab.domain.model.Connection.ConnectionAuditEntry;
import com.thinklab.domain.model.Connection.ConnectionStatus;
import com.thinklab.domain.model.Connection.InboundAction;
import com.thinklab.domain.model.Connection.Provider;
import io.micronaut.core.annotation.Introspected;
import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Infrastructure-specific representation of the Connection Aggregate for MongoDB. It holds the NAMES of the secrets, never their values
 * (ADR-032). The two maps are stored as lists of pairs: a status name is free text and Mongo does not allow dots or a leading dollar in a
 * field name.
 */
@Introspected
public class ConnectionDocument {

    @BsonId
    private UUID id;

    private UUID organisationId;
    private String name;
    private String provider;
    private String baseUrl;
    private String secretRef;
    private String webhookSecretRef;
    private String integrationActor;
    private String projectKey;
    private List<MappingDocument> outboundStatus = new ArrayList<>();
    private List<MappingDocument> inboundActions = new ArrayList<>();
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
    private List<AuditEntryDocument> auditTrail = new ArrayList<>();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getOrganisationId() { return organisationId; }
    public void setOrganisationId(UUID organisationId) { this.organisationId = organisationId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getSecretRef() { return secretRef; }
    public void setSecretRef(String secretRef) { this.secretRef = secretRef; }
    public String getWebhookSecretRef() { return webhookSecretRef; }
    public void setWebhookSecretRef(String webhookSecretRef) { this.webhookSecretRef = webhookSecretRef; }
    public String getIntegrationActor() { return integrationActor; }
    public void setIntegrationActor(String integrationActor) { this.integrationActor = integrationActor; }
    public String getProjectKey() { return projectKey; }
    public void setProjectKey(String projectKey) { this.projectKey = projectKey; }
    public List<MappingDocument> getOutboundStatus() { return outboundStatus; }
    public void setOutboundStatus(List<MappingDocument> outboundStatus) { this.outboundStatus = outboundStatus; }
    public List<MappingDocument> getInboundActions() { return inboundActions; }
    public void setInboundActions(List<MappingDocument> inboundActions) { this.inboundActions = inboundActions; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public List<AuditEntryDocument> getAuditTrail() { return auditTrail; }
    public void setAuditTrail(List<AuditEntryDocument> auditTrail) { this.auditTrail = auditTrail; }

    /** One key/value pair of a map. */
    @Introspected
    public record MappingDocument(String key, String value) {
    }

    @Introspected
    public record AuditEntryDocument(Instant occurredAt, String action, String executor, String fromStatus, String toStatus, String detail) {

        public static AuditEntryDocument fromDomain(ConnectionAuditEntry entry) {
            return new AuditEntryDocument(entry.occurredAt(), entry.action(), entry.executor(),
                    entry.fromStatus() != null ? entry.fromStatus().name() : null, entry.toStatus().name(), entry.detail());
        }

        // toStatus has no null branch: fromDomain always writes entry.toStatus().name().
        ConnectionAuditEntry toDomain() {
            return new ConnectionAuditEntry(occurredAt, action, executor, fromStatus != null ? ConnectionStatus.valueOf(fromStatus) : null,
                    ConnectionStatus.valueOf(toStatus), detail);
        }
    }

    public static List<MappingDocument> outboundToDocument(Map<String, String> map) {
        return map.entrySet().stream().map(e -> new MappingDocument(e.getKey(), e.getValue())).collect(Collectors.toCollection(ArrayList::new));
    }

    public static List<MappingDocument> inboundToDocument(Map<String, InboundAction> map) {
        return map.entrySet().stream().map(e -> new MappingDocument(e.getKey(), e.getValue().name())).collect(Collectors.toCollection(ArrayList::new));
    }

    public static final class ConnectionPersistenceMapper {

        private ConnectionPersistenceMapper() { throw new UnsupportedOperationException(); }

        public static ConnectionDocument toDocument(Connection connection) {
            ConnectionDocument doc = new ConnectionDocument();
            doc.setId(connection.getId());
            doc.setOrganisationId(connection.getOrganisationId());
            doc.setName(connection.getName());
            doc.setProvider(connection.getProvider().name());
            doc.setBaseUrl(connection.getBaseUrl());
            doc.setSecretRef(connection.getSecretRef());
            doc.setWebhookSecretRef(connection.getWebhookSecretRef());
            doc.setIntegrationActor(connection.getIntegrationActor());
            doc.setProjectKey(connection.getProjectKey());
            doc.setOutboundStatus(outboundToDocument(connection.getOutboundStatus()));
            doc.setInboundActions(inboundToDocument(connection.getInboundActions()));
            doc.setStatus(connection.getStatus().name());
            doc.setCreatedAt(connection.getCreatedAt());
            doc.setUpdatedAt(connection.getUpdatedAt());
            doc.setAuditTrail(connection.getAuditTrail().stream().map(AuditEntryDocument::fromDomain).collect(Collectors.toCollection(ArrayList::new)));
            return doc;
        }

        public static Connection toDomain(ConnectionDocument doc) {
            Map<String, String> outbound = new LinkedHashMap<>();
            doc.getOutboundStatus().forEach(pair -> outbound.put(pair.key(), pair.value()));
            Map<String, InboundAction> inbound = new LinkedHashMap<>();
            doc.getInboundActions().forEach(pair -> inbound.put(pair.key(), InboundAction.valueOf(pair.value())));
            return Connection.reconstitute(doc.getId(), doc.getOrganisationId(), doc.getName(), Provider.valueOf(doc.getProvider()), doc.getBaseUrl(),
                    doc.getSecretRef(), doc.getWebhookSecretRef(), doc.getIntegrationActor(), doc.getProjectKey(), outbound, inbound,
                    ConnectionStatus.valueOf(doc.getStatus()), doc.getCreatedAt(), doc.getUpdatedAt(),
                    doc.getAuditTrail().stream().map(AuditEntryDocument::toDomain).collect(Collectors.toList()));
        }
    }
}
