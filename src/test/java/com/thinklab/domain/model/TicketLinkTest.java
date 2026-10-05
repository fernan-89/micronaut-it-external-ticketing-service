package com.thinklab.domain.model;

import com.thinklab.domain.exception.InvalidTicketLinkStatusException;
import com.thinklab.domain.model.TicketLink.Direction;
import com.thinklab.domain.model.TicketLink.LinkAuditEntry;
import com.thinklab.domain.model.TicketLink.LinkStatus;
import com.thinklab.domain.model.TicketLink.SubjectType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TicketLinkTest {

    private final UUID org = UUID.randomUUID();
    private final UUID connection = UUID.randomUUID();
    private final UUID subject = UUID.randomUUID();

    private TicketLink pending() {
        return TicketLink.createNew(UUID.randomUUID(), org, connection, SubjectType.INCIDENT, subject, "op-1");
    }

    private TicketLink linked() {
        TicketLink link = pending();
        link.markCreated("ITSM-1", "https://jira/browse/ITSM-1", "NEW", "op-1");
        return link;
    }

    @Test
    @DisplayName("a new link is PENDING with an INITIATED entry and nothing from the provider yet")
    void create() {
        TicketLink link = pending();

        assertEquals(LinkStatus.PENDING, link.getStatus());
        assertNull(link.getExternalId());
        assertEquals(1, link.getAuditTrail().size());
        assertEquals("INITIATED", link.getAuditTrail().get(0).action());
        assertEquals(SubjectType.INCIDENT, link.getSubjectType());
        assertEquals(subject, link.getSubjectId());
        assertEquals(connection, link.getConnectionId());
        assertEquals(org, link.getOrganisationId());
        assertEquals(link.getCreatedAt(), link.getUpdatedAt());
    }

    @Test
    @DisplayName("creation guards: every id, the subject type and the executor")
    void createGuards() {
        UUID id = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> TicketLink.createNew(null, org, connection, SubjectType.INCIDENT, subject, "op"));
        assertThrows(IllegalArgumentException.class, () -> TicketLink.createNew(id, null, connection, SubjectType.INCIDENT, subject, "op"));
        assertThrows(IllegalArgumentException.class, () -> TicketLink.createNew(id, org, null, SubjectType.INCIDENT, subject, "op"));
        assertThrows(IllegalArgumentException.class, () -> TicketLink.createNew(id, org, connection, null, subject, "op"));
        assertThrows(IllegalArgumentException.class, () -> TicketLink.createNew(id, org, connection, SubjectType.INCIDENT, null, "op"));
        assertThrows(IllegalArgumentException.class, () -> TicketLink.createNew(id, org, connection, SubjectType.INCIDENT, subject, null));
        assertThrows(IllegalArgumentException.class, () -> TicketLink.createNew(id, org, connection, SubjectType.INCIDENT, subject, " "));
    }

    @Test
    @DisplayName("markCreated: PENDING or FAILED become LINKED and remember the ticket and the status it was created with")
    void markCreated() {
        TicketLink link = pending();

        LinkAuditEntry entry = link.markCreated("ITSM-1", "https://jira/browse/ITSM-1", "NEW", "op-2");

        assertEquals(LinkStatus.LINKED, link.getStatus());
        assertEquals("ITSM-1", link.getExternalId());
        assertEquals("https://jira/browse/ITSM-1", link.getExternalUrl());
        assertEquals("NEW", link.getLastPushedStatus());
        assertEquals(Direction.OUTBOUND, link.getLastDirection());
        assertNotNull(link.getLastSyncedAt());
        assertEquals("TICKET_CREATED", entry.action());
        assertEquals(LinkStatus.PENDING, entry.fromStatus());

        TicketLink retry = pending();
        retry.markCreateFailed("boom", "op");
        retry.markCreated("ITSM-2", null, null, "op");
        assertEquals(LinkStatus.LINKED, retry.getStatus());
        assertNull(retry.getLastError());

        assertThrows(InvalidTicketLinkStatusException.class, () -> link.markCreated("ITSM-3", null, null, "op"));
        assertThrows(IllegalArgumentException.class, () -> pending().markCreated(null, null, null, "op"));
        assertThrows(IllegalArgumentException.class, () -> pending().markCreated(" ", null, null, "op"));
    }

    @Test
    @DisplayName("markCreateFailed keeps one short sanitised line; a repeat stays FAILED")
    void markCreateFailed() {
        TicketLink link = pending();

        link.markCreateFailed("line one\nline two\t" + "x".repeat(300), "op");

        assertEquals(LinkStatus.FAILED, link.getStatus());
        assertFalse(link.getLastError().contains("\n"));
        assertEquals(TicketLink.MAX_ERROR_LENGTH, link.getLastError().length());
        link.markCreateFailed(null, "op");
        assertEquals("unknown error", link.getLastError());
        link.markCreateFailed(" ", "op");
        assertEquals("unknown error", link.getLastError());
        assertThrows(InvalidTicketLinkStatusException.class, () -> linked().markCreateFailed("x", "op"));
    }

    @Test
    @DisplayName("recordPush remembers the status and the comments pushed, clears the error, and keeps the status when none was pushed")
    void recordPush() {
        TicketLink link = linked();
        UUID comment = UUID.randomUUID();

        LinkAuditEntry statusOnly = link.recordPush("RESOLVED", Set.of(), true, "op");
        assertEquals("RESOLVED", link.getLastPushedStatus());
        assertTrue(statusOnly.detail().contains("the status"));

        LinkAuditEntry commentOnly = link.recordPush("IGNORED", Set.of(comment), false, "op");
        assertEquals("RESOLVED", link.getLastPushedStatus());
        assertTrue(link.getSyncedCommentIds().contains(comment));
        assertTrue(commentOnly.detail().contains("no status"));
        assertEquals("SYNCED_OUT", commentOnly.action());

        assertThrows(InvalidTicketLinkStatusException.class, () -> pending().recordPush("x", Set.of(), true, "op"));
    }

    @Test
    @DisplayName("the memory of pushed comments is bounded: the oldest are forgotten first")
    void syncedCommentsAreBounded() {
        TicketLink link = linked();
        Set<UUID> first = new HashSet<>();
        UUID oldest = UUID.randomUUID();
        link.recordPush("x", Set.of(oldest), false, "op");
        for (int i = 0; i < TicketLink.MAX_SYNCED_COMMENTS; i++) {
            first.add(UUID.randomUUID());
        }
        link.recordPush("x", first, false, "op");

        assertEquals(TicketLink.MAX_SYNCED_COMMENTS, link.getSyncedCommentIds().size());
        assertFalse(link.getSyncedCommentIds().contains(oldest));
    }

    @Test
    @DisplayName("recordPushFailure keeps the link LINKED with a sanitised error")
    void recordPushFailure() {
        TicketLink link = linked();

        link.recordPushFailure("Jira refused\nreading", "op");

        assertEquals(LinkStatus.LINKED, link.getStatus());
        assertEquals("Jira refused reading", link.getLastError());
        assertThrows(InvalidTicketLinkStatusException.class, () -> pending().recordPushFailure("x", "op"));
    }

    @Test
    @DisplayName("recordInbound makes the new platform status the last pushed one, so it is not echoed back")
    void recordInbound() {
        TicketLink link = linked();
        link.recordPushFailure("x", "op");

        LinkAuditEntry entry = link.recordInbound("Applied RESOLVE.", "RESOLVED", "external-ticketing:c1");

        assertEquals("RESOLVED", link.getLastPushedStatus());
        assertEquals(Direction.INBOUND, link.getLastDirection());
        assertNull(link.getLastError());
        assertEquals("SYNCED_IN", entry.action());

        link.recordInbound("comment only", null, "external-ticketing:c1");
        assertEquals("RESOLVED", link.getLastPushedStatus());
        assertThrows(InvalidTicketLinkStatusException.class, () -> pending().recordInbound("x", null, "op"));
    }

    @Test
    @DisplayName("recordInboundConflict records why the platform kept its own state")
    void recordInboundConflict() {
        TicketLink link = linked();

        LinkAuditEntry entry = link.recordInboundConflict("Illegal transition", "external-ticketing:c1");

        assertEquals("INBOUND_CONFLICT", entry.action());
        assertEquals("Illegal transition", link.getLastError());
        assertEquals(LinkStatus.LINKED, link.getStatus());
        assertThrows(InvalidTicketLinkStatusException.class, () -> pending().recordInboundConflict("x", "op"));
    }

    @Test
    @DisplayName("detach is terminal and allowed from PENDING, LINKED and FAILED")
    void detach() {
        for (TicketLink link : List.of(pending(), linked(), failed())) {
            link.detach("op");
            assertEquals(LinkStatus.DETACHED, link.getStatus());
            assertThrows(InvalidTicketLinkStatusException.class, () -> link.detach("op"));
        }
        assertThrows(IllegalArgumentException.class, () -> pending().detach(null));
    }

    private TicketLink failed() {
        TicketLink link = pending();
        link.markCreateFailed("x", "op");
        return link;
    }

    @Test
    @DisplayName("reconstitute keeps what was stored, defaults what is missing, and refuses a missing identity")
    void reconstitute() {
        UUID id = UUID.randomUUID();
        Instant created = Instant.parse("2026-01-01T00:00:00Z");
        TicketLink full = TicketLink.reconstitute(id, org, connection, SubjectType.PROBLEM, subject, "E-1", "u", LinkStatus.LINKED, "NEW", Set.of(UUID.randomUUID()), created,
                Direction.INBOUND, "err", created, created, List.of());
        assertEquals(LinkStatus.LINKED, full.getStatus());
        assertEquals(1, full.getSyncedCommentIds().size());
        assertEquals(created, full.getLastSyncedAt());
        assertEquals(Direction.INBOUND, full.getLastDirection());

        TicketLink bare = TicketLink.reconstitute(id, org, connection, SubjectType.PROBLEM, subject, null, null, null, null, null, null, null, null, null, null, null);
        assertEquals(LinkStatus.PENDING, bare.getStatus());
        assertTrue(bare.getSyncedCommentIds().isEmpty());
        assertTrue(bare.getAuditTrail().isEmpty());
        assertEquals(bare.getCreatedAt(), bare.getUpdatedAt());

        assertThrows(IllegalArgumentException.class, () -> TicketLink.reconstitute(null, org, connection, SubjectType.PROBLEM, subject, null, null, null, null, null, null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> TicketLink.reconstitute(id, null, connection, SubjectType.PROBLEM, subject, null, null, null, null, null, null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> TicketLink.reconstitute(id, org, null, SubjectType.PROBLEM, subject, null, null, null, null, null, null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> TicketLink.reconstitute(id, org, connection, null, subject, null, null, null, null, null, null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> TicketLink.reconstitute(id, org, connection, SubjectType.PROBLEM, null, null, null, null, null, null, null, null, null, null, null, null));
    }
}
