package com.thinklab.infrastructure.adapter.out.persistence.entity;

import com.thinklab.domain.model.TicketLink;
import com.thinklab.domain.model.TicketLink.Direction;
import com.thinklab.domain.model.TicketLink.LinkAuditEntry;
import com.thinklab.domain.model.TicketLink.LinkStatus;
import com.thinklab.domain.model.TicketLink.SubjectType;
import io.micronaut.core.annotation.Introspected;
import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** Infrastructure-specific representation of the TicketLink Aggregate for MongoDB. Only ids, statuses and times: no ticket text and no credentials (ADR-032). */
@Introspected
public class TicketLinkDocument {

    @BsonId
    private UUID id;

    private UUID organisationId;
    private UUID connectionId;
    private String subjectType;
    private UUID subjectId;
    private String externalId;
    private String externalUrl;
    private String status;
    private String lastPushedStatus;
    private List<UUID> syncedCommentIds = new ArrayList<>();
    private Instant lastSyncedAt;
    private String lastDirection;
    private String lastError;
    private Instant createdAt;
    private Instant updatedAt;
    private List<AuditEntryDocument> auditTrail = new ArrayList<>();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getOrganisationId() { return organisationId; }
    public void setOrganisationId(UUID organisationId) { this.organisationId = organisationId; }
    public UUID getConnectionId() { return connectionId; }
    public void setConnectionId(UUID connectionId) { this.connectionId = connectionId; }
    public String getSubjectType() { return subjectType; }
    public void setSubjectType(String subjectType) { this.subjectType = subjectType; }
    public UUID getSubjectId() { return subjectId; }
    public void setSubjectId(UUID subjectId) { this.subjectId = subjectId; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }
    public String getExternalUrl() { return externalUrl; }
    public void setExternalUrl(String externalUrl) { this.externalUrl = externalUrl; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getLastPushedStatus() { return lastPushedStatus; }
    public void setLastPushedStatus(String lastPushedStatus) { this.lastPushedStatus = lastPushedStatus; }
    public List<UUID> getSyncedCommentIds() { return syncedCommentIds; }
    public void setSyncedCommentIds(List<UUID> syncedCommentIds) { this.syncedCommentIds = syncedCommentIds; }
    public Instant getLastSyncedAt() { return lastSyncedAt; }
    public void setLastSyncedAt(Instant lastSyncedAt) { this.lastSyncedAt = lastSyncedAt; }
    public String getLastDirection() { return lastDirection; }
    public void setLastDirection(String lastDirection) { this.lastDirection = lastDirection; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public List<AuditEntryDocument> getAuditTrail() { return auditTrail; }
    public void setAuditTrail(List<AuditEntryDocument> auditTrail) { this.auditTrail = auditTrail; }

    @Introspected
    public record AuditEntryDocument(Instant occurredAt, String action, String executor, String fromStatus, String toStatus, String detail) {

        public static AuditEntryDocument fromDomain(LinkAuditEntry entry) {
            return new AuditEntryDocument(entry.occurredAt(), entry.action(), entry.executor(),
                    entry.fromStatus() != null ? entry.fromStatus().name() : null, entry.toStatus().name(), entry.detail());
        }

        // toStatus has no null branch: fromDomain always writes entry.toStatus().name().
        LinkAuditEntry toDomain() {
            return new LinkAuditEntry(occurredAt, action, executor, fromStatus != null ? LinkStatus.valueOf(fromStatus) : null,
                    LinkStatus.valueOf(toStatus), detail);
        }
    }

    public static final class TicketLinkPersistenceMapper {

        private TicketLinkPersistenceMapper() { throw new UnsupportedOperationException(); }

        public static TicketLinkDocument toDocument(TicketLink link) {
            TicketLinkDocument doc = new TicketLinkDocument();
            doc.setId(link.getId());
            doc.setOrganisationId(link.getOrganisationId());
            doc.setConnectionId(link.getConnectionId());
            doc.setSubjectType(link.getSubjectType().name());
            doc.setSubjectId(link.getSubjectId());
            doc.setExternalId(link.getExternalId());
            doc.setExternalUrl(link.getExternalUrl());
            doc.setStatus(link.getStatus().name());
            doc.setLastPushedStatus(link.getLastPushedStatus());
            doc.setSyncedCommentIds(new ArrayList<>(link.getSyncedCommentIds()));
            doc.setLastSyncedAt(link.getLastSyncedAt());
            doc.setLastDirection(link.getLastDirection() != null ? link.getLastDirection().name() : null);
            doc.setLastError(link.getLastError());
            doc.setCreatedAt(link.getCreatedAt());
            doc.setUpdatedAt(link.getUpdatedAt());
            doc.setAuditTrail(link.getAuditTrail().stream().map(AuditEntryDocument::fromDomain).collect(Collectors.toCollection(ArrayList::new)));
            return doc;
        }

        public static TicketLink toDomain(TicketLinkDocument doc) {
            return TicketLink.reconstitute(doc.getId(), doc.getOrganisationId(), doc.getConnectionId(), SubjectType.valueOf(doc.getSubjectType()),
                    doc.getSubjectId(), doc.getExternalId(), doc.getExternalUrl(), LinkStatus.valueOf(doc.getStatus()), doc.getLastPushedStatus(),
                    new LinkedHashSet<>(doc.getSyncedCommentIds()), doc.getLastSyncedAt(),
                    doc.getLastDirection() != null ? Direction.valueOf(doc.getLastDirection()) : null, doc.getLastError(),
                    doc.getCreatedAt(), doc.getUpdatedAt(), doc.getAuditTrail().stream().map(AuditEntryDocument::toDomain).collect(Collectors.toList()));
        }
    }
}
