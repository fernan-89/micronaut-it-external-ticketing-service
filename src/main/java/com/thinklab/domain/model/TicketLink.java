package com.thinklab.domain.model;

import com.thinklab.domain.exception.InvalidTicketLinkStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Core Domain Model representing the TicketLink Aggregate Root (BIAN Service Domain: {@code it-external-ticketing}): the pairing of one
 * incident, service request or problem of the platform with its ticket in ServiceNow or Jira.
 *
 * <p><b>Lifecycle (ADR-030):</b> {@code PENDING -> LINKED}, or {@code PENDING -> FAILED -> LINKED} when the provider was down at the first
 * attempt, and {@code DETACHED} (terminal) when the pairing is dropped. The link is saved {@code PENDING} <i>before</i> the provider is
 * called, so a crash between the two leaves a link to retry rather than an orphan ticket nobody knows about; a retry (a sync) creates the
 * ticket if there is none yet.
 *
 * <p><b>What it remembers, and what it does not (ADR-032):</b> only ids, statuses and times: the platform status last pushed (so a status
 * is pushed once, not on every sync, and one that came <i>from</i> the provider is never pushed back), the ids of the platform comments
 * already pushed, and a short sanitised error. Never a ticket text, a comment or a credential.
 *
 * <p>Strictly pure Java. Agnostic of frameworks, databases, or web layers.
 */
public class TicketLink {

    public static final int MAX_SYNCED_COMMENTS = 500;
    public static final int MAX_ERROR_LENGTH = 200;

    private final UUID id;
    private final UUID organisationId;
    private final UUID connectionId;
    private final SubjectType subjectType;
    private final UUID subjectId;
    private String externalId;
    private String externalUrl;
    private LinkStatus status;
    private String lastPushedStatus;
    private Set<UUID> syncedCommentIds;
    private Instant lastSyncedAt;
    private Direction lastDirection;
    private String lastError;
    private final Instant createdAt;
    private Instant updatedAt;
    private final List<LinkAuditEntry> auditTrail;

    private TicketLink(UUID id, UUID organisationId, UUID connectionId, SubjectType subjectType, UUID subjectId, String executor) {
        this.id = id;
        this.organisationId = organisationId;
        this.connectionId = connectionId;
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.status = LinkStatus.PENDING;
        this.syncedCommentIds = new LinkedHashSet<>();
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.auditTrail = new ArrayList<>();
        this.auditTrail.add(new LinkAuditEntry(this.createdAt, "INITIATED", executor, null, LinkStatus.PENDING, "Link to a " + subjectType + " requested."));
    }

    private TicketLink(UUID id, UUID organisationId, UUID connectionId, SubjectType subjectType, UUID subjectId, String externalId, String externalUrl,
                       LinkStatus status, String lastPushedStatus, Set<UUID> syncedCommentIds, Instant lastSyncedAt, Direction lastDirection,
                       String lastError, Instant createdAt, Instant updatedAt, List<LinkAuditEntry> auditTrail) {
        this.id = id;
        this.organisationId = organisationId;
        this.connectionId = connectionId;
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.externalId = externalId;
        this.externalUrl = externalUrl;
        this.status = status != null ? status : LinkStatus.PENDING;
        this.lastPushedStatus = lastPushedStatus;
        this.syncedCommentIds = syncedCommentIds != null ? new LinkedHashSet<>(syncedCommentIds) : new LinkedHashSet<>();
        this.lastSyncedAt = lastSyncedAt;
        this.lastDirection = lastDirection;
        this.lastError = lastError;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.updatedAt = updatedAt != null ? updatedAt : this.createdAt;
        this.auditTrail = auditTrail != null ? new ArrayList<>(auditTrail) : new ArrayList<>();
    }

    public static TicketLink createNew(UUID id, UUID organisationId, UUID connectionId, SubjectType subjectType, UUID subjectId, String executor) {
        if (id == null || organisationId == null || connectionId == null || subjectType == null || subjectId == null) {
            throw new IllegalArgumentException("ID, Organisation ID, Connection ID, the subject type and the subject ID are mandatory for TicketLink creation.");
        }
        requireExecutor(executor);
        return new TicketLink(id, organisationId, connectionId, subjectType, subjectId, executor);
    }

    public static TicketLink reconstitute(UUID id, UUID organisationId, UUID connectionId, SubjectType subjectType, UUID subjectId, String externalId,
                                          String externalUrl, LinkStatus status, String lastPushedStatus, Set<UUID> syncedCommentIds, Instant lastSyncedAt,
                                          Direction lastDirection, String lastError, Instant createdAt, Instant updatedAt, List<LinkAuditEntry> auditTrail) {
        if (id == null || organisationId == null || connectionId == null || subjectType == null || subjectId == null) {
            throw new IllegalArgumentException("ID, Organisation ID, Connection ID, the subject type and the subject ID are mandatory to reconstitute a TicketLink.");
        }
        return new TicketLink(id, organisationId, connectionId, subjectType, subjectId, externalId, externalUrl, status, lastPushedStatus, syncedCommentIds,
                lastSyncedAt, lastDirection, lastError, createdAt, updatedAt, auditTrail);
    }

    // --- Domain Behaviors ---

    /** The provider created the ticket: PENDING or FAILED -&gt; LINKED. {@code platformStatus} is what the ticket was created with. */
    public LinkAuditEntry markCreated(String newExternalId, String newExternalUrl, String platformStatus, String executor) {
        requireStatus(LinkStatus.PENDING, LinkStatus.FAILED);
        if (newExternalId == null || newExternalId.isBlank()) {
            throw new IllegalArgumentException("The external ticket id is mandatory to mark a link as created.");
        }
        this.externalId = newExternalId;
        this.externalUrl = newExternalUrl;
        this.lastPushedStatus = platformStatus;
        this.lastError = null;
        this.lastDirection = Direction.OUTBOUND;
        this.lastSyncedAt = Instant.now();
        return transition(LinkStatus.LINKED, "TICKET_CREATED", executor, "Ticket " + newExternalId + " created at the provider.");
    }

    /** The provider could not create the ticket: PENDING or FAILED -&gt; FAILED, to be retried by a sync. */
    public LinkAuditEntry markCreateFailed(String error, String executor) {
        requireStatus(LinkStatus.PENDING, LinkStatus.FAILED);
        this.lastError = sanitise(error);
        return transition(LinkStatus.FAILED, "CREATE_FAILED", executor, "The provider could not create the ticket: " + this.lastError);
    }

    /** A push reached the provider (the status and/or comments): the link stays LINKED. */
    public LinkAuditEntry recordPush(String platformStatus, Set<UUID> pushedCommentIds, boolean statusPushed, String executor) {
        requireStatus(LinkStatus.LINKED);
        if (statusPushed) {
            this.lastPushedStatus = platformStatus;
        }
        this.syncedCommentIds.addAll(pushedCommentIds);
        while (this.syncedCommentIds.size() > MAX_SYNCED_COMMENTS) {
            this.syncedCommentIds.remove(this.syncedCommentIds.iterator().next());
        }
        this.lastError = null;
        this.lastDirection = Direction.OUTBOUND;
        this.lastSyncedAt = Instant.now();
        return record("SYNCED_OUT", executor, String.format("Pushed %s and %d comment(s).", statusPushed ? "the status" : "no status", pushedCommentIds.size()));
    }

    /** A push failed: the link stays LINKED and retryable; only a short sanitised error is kept. */
    public LinkAuditEntry recordPushFailure(String error, String executor) {
        requireStatus(LinkStatus.LINKED);
        this.lastError = sanitise(error);
        return record("SYNC_FAILED", executor, "The provider refused the push: " + this.lastError);
    }

    /**
     * Something came from the provider and was applied to the platform. {@code newPlatformStatus} (when the platform status changed) becomes the
     * last status pushed, so it is not pushed back to the provider it came from.
     */
    public LinkAuditEntry recordInbound(String detail, String newPlatformStatus, String executor) {
        requireStatus(LinkStatus.LINKED);
        if (newPlatformStatus != null) {
            this.lastPushedStatus = newPlatformStatus;
        }
        this.lastError = null;
        this.lastDirection = Direction.INBOUND;
        this.lastSyncedAt = Instant.now();
        return record("SYNCED_IN", executor, detail);
    }

    /** Something came from the provider and the platform refused it: the platform wins (ADR-031), the reason is recorded. */
    public LinkAuditEntry recordInboundConflict(String reason, String executor) {
        requireStatus(LinkStatus.LINKED);
        this.lastError = sanitise(reason);
        return record("INBOUND_CONFLICT", executor, "The platform kept its own state: " + this.lastError);
    }

    /** Behavior Qualifier: {@code control/detach}. The pairing is dropped (terminal); the ticket at the provider is left as it is. */
    public LinkAuditEntry detach(String executor) {
        requireStatus(LinkStatus.PENDING, LinkStatus.LINKED, LinkStatus.FAILED);
        return transition(LinkStatus.DETACHED, "DETACHED", executor, "Detached: the platform and the provider no longer follow each other.");
    }

    // --- Internal helpers ---

    private LinkAuditEntry transition(LinkStatus newStatus, String action, String executor, String detail) {
        requireExecutor(executor);
        LinkStatus previous = this.status;
        this.status = newStatus;
        this.updatedAt = Instant.now();
        LinkAuditEntry entry = new LinkAuditEntry(this.updatedAt, action, executor, previous, newStatus, detail);
        this.auditTrail.add(entry);
        return entry;
    }

    private LinkAuditEntry record(String action, String executor, String detail) {
        requireExecutor(executor);
        this.updatedAt = Instant.now();
        LinkAuditEntry entry = new LinkAuditEntry(this.updatedAt, action, executor, this.status, this.status, detail);
        this.auditTrail.add(entry);
        return entry;
    }

    private void requireStatus(LinkStatus... allowed) {
        if (Arrays.asList(allowed).contains(this.status)) {
            return;
        }
        throw new InvalidTicketLinkStatusException(String.format(
                "Illegal transition: TicketLink is [%s], expected one of %s.", this.status, Arrays.toString(allowed)));
    }

    /** One line, bounded: whatever a provider says never becomes a stored paragraph. */
    private static String sanitise(String error) {
        String line = error == null || error.isBlank() ? "unknown error" : error.replaceAll("[\\r\\n\\t]+", " ").trim();
        return line.length() > MAX_ERROR_LENGTH ? line.substring(0, MAX_ERROR_LENGTH) : line;
    }

    private static void requireExecutor(String executor) {
        if (executor == null || executor.isBlank()) {
            throw new IllegalArgumentException("Executor is mandatory for auditable TicketLink mutations.");
        }
    }

    // --- Getters ---

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public UUID getConnectionId() { return connectionId; }
    public SubjectType getSubjectType() { return subjectType; }
    public UUID getSubjectId() { return subjectId; }
    public String getExternalId() { return externalId; }
    public String getExternalUrl() { return externalUrl; }
    public LinkStatus getStatus() { return status; }
    public String getLastPushedStatus() { return lastPushedStatus; }
    public Set<UUID> getSyncedCommentIds() { return Collections.unmodifiableSet(syncedCommentIds); }
    public Instant getLastSyncedAt() { return lastSyncedAt; }
    public Direction getLastDirection() { return lastDirection; }
    public String getLastError() { return lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<LinkAuditEntry> getAuditTrail() { return Collections.unmodifiableList(auditTrail); }

    // --- Nested Value Objects ---

    /** What the platform item is. */
    public enum SubjectType { INCIDENT, SERVICE_REQUEST, PROBLEM }

    /** PENDING -&gt; LINKED (or FAILED -&gt; LINKED); DETACHED is terminal. */
    public enum LinkStatus { PENDING, LINKED, FAILED, DETACHED }

    /** Which way the last exchange went. */
    public enum Direction { OUTBOUND, INBOUND }

    /** Immutable forensic ledger entry, mirroring the platform's established audit-trail pattern. */
    public record LinkAuditEntry(Instant occurredAt, String action, String executor, LinkStatus fromStatus, LinkStatus toStatus, String detail) {}
}
