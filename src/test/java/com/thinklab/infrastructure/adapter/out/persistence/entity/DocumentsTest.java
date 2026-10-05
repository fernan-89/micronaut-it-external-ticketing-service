package com.thinklab.infrastructure.adapter.out.persistence.entity;

import com.thinklab.domain.model.Connection;
import com.thinklab.domain.model.Connection.InboundAction;
import com.thinklab.domain.model.Connection.Provider;
import com.thinklab.domain.model.TicketLink;
import com.thinklab.domain.model.TicketLink.SubjectType;
import com.thinklab.infrastructure.adapter.out.persistence.entity.ConnectionDocument.ConnectionPersistenceMapper;
import com.thinklab.infrastructure.adapter.out.persistence.entity.TicketLinkDocument.TicketLinkPersistenceMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DocumentsTest {

    private final UUID org = UUID.randomUUID();

    @Test
    @DisplayName("a connection survives the round trip: maps with dots in their keys, the disabled status, the audit trail; only secret NAMES are stored")
    void connectionRoundTrip() {
        Connection connection = Connection.createNew(UUID.randomUUID(), org, "Jira", Provider.JIRA, "https://acme.atlassian.net", "JIRA_AUTH", "JIRA_HOOK", "svc", "ITSM",
                Map.of("NEW", "To Do (v1.2)"), Map.of("Done. Really", InboundAction.RESOLVE), "op");
        connection.disable("op-2");

        ConnectionDocument document = ConnectionPersistenceMapper.toDocument(connection);
        Connection back = ConnectionPersistenceMapper.toDomain(document);

        assertEquals("JIRA_AUTH", document.getSecretRef());
        assertEquals(connection.getOutboundStatus(), back.getOutboundStatus());
        assertEquals(connection.getInboundActions(), back.getInboundActions());
        assertEquals(connection.getStatus(), back.getStatus());
        assertEquals(connection.getProjectKey(), back.getProjectKey());
        assertEquals(2, back.getAuditTrail().size());
        assertNull(back.getAuditTrail().get(0).fromStatus());
        assertEquals(connection.getAuditTrail().get(1).fromStatus(), back.getAuditTrail().get(1).fromStatus());
        assertEquals(connection.getIntegrationActor(), back.getIntegrationActor());
        assertEquals(connection.getWebhookSecretRef(), back.getWebhookSecretRef());
        assertEquals(connection.getBaseUrl(), back.getBaseUrl());
        assertEquals(connection.getName(), back.getName());
        assertEquals(connection.getCreatedAt(), document.getCreatedAt());
        assertEquals(connection.getUpdatedAt(), document.getUpdatedAt());
        assertEquals(connection.getId(), document.getId());
        assertEquals(Provider.JIRA.name(), document.getProvider());
    }

    @Test
    @DisplayName("a ticket link survives the round trip, including a link that has nothing from the provider yet")
    void linkRoundTrip() {
        TicketLink pending = TicketLink.createNew(UUID.randomUUID(), org, UUID.randomUUID(), SubjectType.PROBLEM, UUID.randomUUID(), "op");
        TicketLink pendingBack = TicketLinkPersistenceMapper.toDomain(TicketLinkPersistenceMapper.toDocument(pending));
        assertNull(pendingBack.getLastDirection());
        assertNull(pendingBack.getExternalId());
        assertEquals(1, pendingBack.getAuditTrail().size());

        UUID comment = UUID.randomUUID();
        TicketLink linked = TicketLink.createNew(UUID.randomUUID(), org, UUID.randomUUID(), SubjectType.INCIDENT, UUID.randomUUID(), "op");
        linked.markCreated("ITSM-1", "u", "NEW", "op");
        linked.recordPush("NEW", Set.of(comment), false, "op");
        linked.recordInbound("x", "RESOLVED", "op");

        TicketLinkDocument document = TicketLinkPersistenceMapper.toDocument(linked);
        TicketLink back = TicketLinkPersistenceMapper.toDomain(document);

        assertEquals("INBOUND", document.getLastDirection());
        assertEquals(Set.of(comment), back.getSyncedCommentIds());
        assertEquals("RESOLVED", back.getLastPushedStatus());
        assertEquals(linked.getStatus(), back.getStatus());
        assertEquals(linked.getLastSyncedAt(), back.getLastSyncedAt());
        assertEquals(linked.getAuditTrail().size(), back.getAuditTrail().size());
        assertEquals(linked.getAuditTrail().get(1).fromStatus(), back.getAuditTrail().get(1).fromStatus());
        assertEquals(linked.getExternalUrl(), document.getExternalUrl());
        assertEquals(linked.getLastError(), document.getLastError());
        assertEquals(linked.getCreatedAt(), document.getCreatedAt());
        assertEquals(linked.getUpdatedAt(), document.getUpdatedAt());
        assertEquals(linked.getSubjectId(), document.getSubjectId());
        assertEquals(linked.getConnectionId(), document.getConnectionId());
        assertEquals(org, document.getOrganisationId());
        assertEquals(linked.getId(), document.getId());
    }

    @Test
    @DisplayName("the persistence mappers are utility classes")
    void utilityClasses() throws Exception {
        for (Class<?> type : new Class<?>[]{ConnectionPersistenceMapper.class, TicketLinkPersistenceMapper.class}) {
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            assertThrows(InvocationTargetException.class, constructor::newInstance);
        }
    }
}
