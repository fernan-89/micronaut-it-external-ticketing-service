package com.thinklab.infrastructure.adapter.out.persistence;

import com.mongodb.reactivestreams.client.MongoClient;
import com.thinklab.domain.exception.DuplicateConnectionException;
import com.thinklab.domain.exception.DuplicateTicketLinkException;
import com.thinklab.domain.exception.InvalidConnectionStatusException;
import com.thinklab.domain.exception.InvalidTicketLinkStatusException;
import com.thinklab.domain.model.Connection;
import com.thinklab.domain.model.Connection.ConnectionStatus;
import com.thinklab.domain.model.Connection.InboundAction;
import com.thinklab.domain.model.Connection.Provider;
import com.thinklab.domain.model.TicketLink;
import com.thinklab.domain.model.TicketLink.LinkStatus;
import com.thinklab.domain.model.TicketLink.SubjectType;
import com.thinklab.domain.repository.ConnectionRepository;
import com.thinklab.domain.repository.ProcessedEventRepository;
import com.thinklab.domain.repository.TicketLinkRepository;
import com.thinklab.domain.repository.TicketLinkRepository.Filter;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The connector's aggregates through their repositories against a real MongoDB: the unique indexes that are the atomic backstops (one
 * connection name per organisation, one link per item and connection, one link per provider ticket, one processed webhook event), the
 * guarded saves (a second writer from the same state loses), tenant scoping, and the webhook idempotency under concurrency.
 */
@MicronautTest(packages = "com.thinklab", transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExternalTicketingPersistenceIT implements TestPropertyProvider {

    private static final String DATABASE = "external_ticketing_it";
    private static final String EXECUTOR = "op-1";

    @Override
    public Map<String, String> getProperties() {
        return Map.of("mongodb.uri", MongoContainer.uri(DATABASE));
    }

    @Inject ConnectionRepository connections;
    @Inject TicketLinkRepository links;
    @Inject ProcessedEventRepository events;
    @Inject MongoClient mongoClient;

    private Connection newConnection(UUID organisation, String name) {
        return Connection.createNew(UUID.randomUUID(), organisation, name, Provider.JIRA, "https://acme.atlassian.net", "JIRA_AUTH", "JIRA_HOOK", "svc", "ITSM",
                Map.of("NEW", "To Do (v1.2)"), Map.of("Done. Really", InboundAction.RESOLVE), EXECUTOR);
    }

    private TicketLink newLink(UUID organisation, UUID connection, UUID subject) {
        return TicketLink.createNew(UUID.randomUUID(), organisation, connection, SubjectType.INCIDENT, subject, EXECUTOR);
    }

    @Test
    @DisplayName("a connection is read back whole, maps whose keys have dots included, and only for its own organisation (the webhook may ask without one)")
    void connectionRoundTrip() {
        UUID organisation = UUID.randomUUID();
        Connection created = connections.create(newConnection(organisation, "Jira prod")).block();

        Connection found = connections.findById(created.getId(), organisation).block();

        assertEquals("JIRA_AUTH", found.getSecretRef());
        assertEquals(Map.of("NEW", "To Do (v1.2)"), found.getOutboundStatus());
        assertEquals(Map.of("Done. Really", InboundAction.RESOLVE), found.getInboundActions());
        assertEquals(1, found.getAuditTrail().size());
        assertNull(connections.findById(created.getId(), UUID.randomUUID()).block());
        assertEquals(created.getId(), connections.findByIdAnyTenant(created.getId()).block().getId());
        assertTrue(connections.existsByName("Jira prod", organisation).block());
        assertEquals(1, connections.findAll(organisation).collectList().block().size());
    }

    @Test
    @DisplayName("two connections with the same name in one organisation: the second loses, in another organisation it is fine")
    void connectionNameIsUnique() {
        UUID organisation = UUID.randomUUID();
        connections.create(newConnection(organisation, "Same name")).block();

        assertThrows(DuplicateConnectionException.class, () -> connections.create(newConnection(organisation, "Same name")).block());
        connections.create(newConnection(UUID.randomUUID(), "Same name")).block();
    }

    @Test
    @DisplayName("a connection's save is guarded by the status it was loaded with: the second writer from the same state loses")
    void connectionGuardedSave() {
        UUID organisation = UUID.randomUUID();
        Connection created = connections.create(newConnection(organisation, "Guarded")).block();
        Connection first = connections.findById(created.getId(), organisation).block();
        Connection second = connections.findById(created.getId(), organisation).block();

        connections.save(first, ConnectionStatus.ACTIVE, first.disable(EXECUTOR)).block();
        assertThrows(InvalidConnectionStatusException.class, () -> connections.save(second, ConnectionStatus.ACTIVE, second.disable(EXECUTOR)).block());

        Connection stored = connections.findById(created.getId(), organisation).block();
        assertEquals(ConnectionStatus.DISABLED, stored.getStatus());
        assertEquals(2, stored.getAuditTrail().size());
    }

    @Test
    @DisplayName("the same item cannot be linked twice to a connection, but links without a ticket yet do not collide with each other")
    void linkUniqueness() {
        UUID organisation = UUID.randomUUID();
        UUID connection = UUID.randomUUID();
        UUID subject = UUID.randomUUID();
        links.create(newLink(organisation, connection, subject)).block();

        assertThrows(DuplicateTicketLinkException.class, () -> links.create(newLink(organisation, connection, subject)).block());
        links.create(newLink(organisation, connection, UUID.randomUUID())).block();
        links.create(newLink(organisation, UUID.randomUUID(), subject)).block();
    }

    @Test
    @DisplayName("one provider ticket belongs to one link: the second link to claim it loses")
    void externalTicketIsUnique() {
        UUID organisation = UUID.randomUUID();
        UUID connection = UUID.randomUUID();
        TicketLink a = links.create(newLink(organisation, connection, UUID.randomUUID())).block();
        TicketLink b = links.create(newLink(organisation, connection, UUID.randomUUID())).block();
        links.save(a, LinkStatus.PENDING, a.markCreated("ITSM-1", "u", "NEW", EXECUTOR)).block();

        assertThrows(DuplicateTicketLinkException.class, () -> links.save(b, LinkStatus.PENDING, b.markCreated("ITSM-1", "u", "NEW", EXECUTOR)).block());
        assertEquals(a.getId(), links.findByExternalId(connection, "ITSM-1", organisation).block().getId());
        assertNull(links.findByExternalId(connection, "ITSM-1", UUID.randomUUID()).block());
    }

    @Test
    @DisplayName("a link's save is guarded by its status; the pushed comments and the status survive; every filter of the list works")
    void linkGuardedSaveAndFilters() {
        UUID organisation = UUID.randomUUID();
        UUID connection = UUID.randomUUID();
        UUID subject = UUID.randomUUID();
        UUID comment = UUID.randomUUID();
        TicketLink created = links.create(newLink(organisation, connection, subject)).block();
        TicketLink first = links.findById(created.getId(), organisation).block();
        TicketLink second = links.findById(created.getId(), organisation).block();

        links.save(first, LinkStatus.PENDING, first.markCreated("ITSM-2", "u", "NEW", EXECUTOR)).block();
        assertThrows(InvalidTicketLinkStatusException.class, () -> links.save(second, LinkStatus.PENDING, second.markCreated("ITSM-3", "u", "NEW", EXECUTOR)).block());
        TicketLink linked = links.findById(created.getId(), organisation).block();
        links.save(linked, LinkStatus.LINKED, linked.recordPush("RESOLVED", Set.of(comment), true, EXECUTOR)).block();

        TicketLink stored = links.findById(created.getId(), organisation).block();
        assertEquals(LinkStatus.LINKED, stored.getStatus());
        assertEquals("RESOLVED", stored.getLastPushedStatus());
        assertEquals(Set.of(comment), stored.getSyncedCommentIds());
        assertEquals(3, stored.getAuditTrail().size());

        assertEquals(Set.of(created.getId()), ids(links.findAll(organisation, new Filter(connection, SubjectType.INCIDENT, subject, LinkStatus.LINKED)).collectList().block()));
        assertTrue(links.findAll(organisation, new Filter(null, SubjectType.PROBLEM, null, null)).collectList().block().isEmpty());
        assertTrue(links.findAll(organisation, new Filter(null, null, null, LinkStatus.DETACHED)).collectList().block().isEmpty());
        assertTrue(links.findAll(UUID.randomUUID(), new Filter(null, null, null, null)).collectList().block().isEmpty());
    }

    private static Set<UUID> ids(List<TicketLink> found) {
        return found.stream().map(TicketLink::getId).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("a webhook event is processed once, even when it is delivered 20 times at the same instant")
    void eventsAreIdempotent() {
        UUID connection = UUID.randomUUID();

        assertTrue(events.markProcessed(connection, "evt-1").block());
        assertEquals(false, events.markProcessed(connection, "evt-1").block());
        assertTrue(events.markProcessed(connection, "evt-2").block());
        assertTrue(events.markProcessed(UUID.randomUUID(), "evt-1").block());

        long firsts = Flux.range(0, 20).flatMap(i -> events.markProcessed(connection, "evt-race")).filter(first -> first).count().block();
        assertEquals(1, firsts);
    }

    @Test
    @DisplayName("startup created the unique indexes and the TTL index")
    void indexesExist() {
        Set<String> links = indexNames("ticket_links");
        assertTrue(links.contains("organisationId_1_connectionId_1_subjectType_1_subjectId_1"));
        assertTrue(links.contains("organisationId_1_connectionId_1_externalId_1"));
        assertTrue(indexNames("connections").contains("organisationId_1_name_1"));
        Set<String> events = indexNames("processed_events");
        assertTrue(events.contains("connectionId_1_eventId_1"));
        assertTrue(events.contains("processedAt_ttl"));
    }

    private Set<String> indexNames(String collection) {
        return Flux.from(mongoClient.getDatabase(DATABASE).getCollection(collection).listIndexes()).map(index -> ((Document) index).getString("name"))
                .collect(Collectors.toSet()).block();
    }
}
