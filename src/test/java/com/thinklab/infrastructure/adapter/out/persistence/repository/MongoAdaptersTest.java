package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;
import com.mongodb.client.result.InsertOneResult;
import com.mongodb.client.result.UpdateResult;
import com.mongodb.reactivestreams.client.FindPublisher;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import com.thinklab.domain.exception.DuplicateConnectionException;
import com.thinklab.domain.exception.DuplicateTicketLinkException;
import com.thinklab.domain.exception.InvalidConnectionStatusException;
import com.thinklab.domain.exception.InvalidTicketLinkStatusException;
import com.thinklab.domain.model.Connection;
import com.thinklab.domain.model.Connection.ConnectionStatus;
import com.thinklab.domain.model.Connection.Provider;
import com.thinklab.domain.model.TicketLink;
import com.thinklab.domain.model.TicketLink.LinkStatus;
import com.thinklab.domain.model.TicketLink.SubjectType;
import com.thinklab.domain.repository.TicketLinkRepository.Filter;
import com.thinklab.infrastructure.adapter.out.persistence.entity.ConnectionDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.ConnectionDocument.ConnectionPersistenceMapper;
import com.thinklab.infrastructure.adapter.out.persistence.entity.TicketLinkDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.TicketLinkDocument.TicketLinkPersistenceMapper;
import org.bson.BsonDocument;
import org.bson.BsonObjectId;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class MongoAdaptersTest {

    private static final String URI = "mongodb://localhost:27017/etk_test";

    private final UUID org = UUID.randomUUID();
    private MongoClient client;
    private MongoDatabase database;
    private MongoCollection<ConnectionDocument> connections;
    private MongoCollection<TicketLinkDocument> links;
    private MongoCollection<Document> events;

    @BeforeEach
    void setUp() {
        client = mock(MongoClient.class);
        database = mock(MongoDatabase.class);
        connections = mock(MongoCollection.class);
        links = mock(MongoCollection.class);
        events = mock(MongoCollection.class);
        when(client.getDatabase("etk_test")).thenReturn(database);
        when(client.getDatabase("thinklab_it_external_ticketing_db")).thenReturn(database);
        when(database.getCollection("connections", ConnectionDocument.class)).thenReturn(connections);
        when(database.getCollection("ticket_links", TicketLinkDocument.class)).thenReturn(links);
        when(database.getCollection("processed_events")).thenReturn(events);
        when(connections.withCodecRegistry(any())).thenReturn(connections);
        when(links.withCodecRegistry(any())).thenReturn(links);
    }

    private static MongoWriteException writeError(int code) {
        return new MongoWriteException(new WriteError(code, "write error", new BsonDocument()), new ServerAddress());
    }

    private Connection connection() {
        return Connection.createNew(UUID.randomUUID(), org, "Jira", Provider.JIRA, "https://acme.atlassian.net", "JIRA_AUTH", "JIRA_HOOK", "svc", "ITSM", null, null, "op");
    }

    private TicketLink link() {
        TicketLink link = TicketLink.createNew(UUID.randomUUID(), org, UUID.randomUUID(), SubjectType.INCIDENT, UUID.randomUUID(), "op");
        link.markCreated("ITSM-1", "u", "NEW", "op");
        return link;
    }

    private <T> void finds(MongoCollection<T> collection, java.util.function.Function<Object, T> toDocument, Object... found) {
        FindPublisher<T> publisher = mock(FindPublisher.class);
        when(collection.find(any(Bson.class))).thenReturn(publisher);
        when(publisher.first()).thenReturn(publisher);
        doAnswer(invocation -> {
            org.reactivestreams.Subscriber<T> subscriber = invocation.getArgument(0);
            Flux.fromArray(found).map(toDocument).subscribe(subscriber);
            return null;
        }).when(publisher).subscribe(any());
    }

    // ------------------------------------------------------------------ Connections

    @Test
    @DisplayName("connections: create, lookups scoped to the organisation (and one that is not, for the webhook), exists, list")
    void connectionLookups() {
        Connection connection = connection();
        when(connections.insertOne(any(ConnectionDocument.class))).thenReturn(Mono.just(InsertOneResult.acknowledged(new BsonObjectId(new ObjectId()))));
        finds(connections, found -> ConnectionPersistenceMapper.toDocument((Connection) found), connection);
        ConnectionMongoRepositoryAdapter adapter = new ConnectionMongoRepositoryAdapter(client, URI);

        StepVerifier.create(adapter.create(connection)).expectNext(connection).verifyComplete();
        StepVerifier.create(adapter.findById(connection.getId(), org)).assertNext(found -> assertEquals(connection.getId(), found.getId())).verifyComplete();
        StepVerifier.create(adapter.findByIdAnyTenant(connection.getId())).assertNext(found -> assertEquals(connection.getId(), found.getId())).verifyComplete();
        StepVerifier.create(adapter.findAll(org)).expectNextCount(1).verifyComplete();

        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        verify(connections, times(3)).find(filter.capture());
        assertTrue(filter.getAllValues().get(0).toString().contains("organisationId"));
        assertFalse(filter.getAllValues().get(1).toString().contains("organisationId"));
    }

    @Test
    @DisplayName("connections: existsByName counts within the organisation")
    void connectionExists() {
        when(connections.countDocuments(any(Bson.class))).thenReturn(Mono.just(1L)).thenReturn(Mono.just(0L));
        ConnectionMongoRepositoryAdapter adapter = new ConnectionMongoRepositoryAdapter(client, URI);

        StepVerifier.create(adapter.existsByName("Jira", org)).expectNext(true).verifyComplete();
        StepVerifier.create(adapter.existsByName("Jira", org)).expectNext(false).verifyComplete();
    }

    @Test
    @DisplayName("connections: the unique index's duplicate key is a 409 of the domain; another write error passes through; the URI may omit the database")
    void connectionDuplicates() {
        Connection connection = connection();
        when(connections.insertOne(any(ConnectionDocument.class))).thenReturn(Mono.error(writeError(11000))).thenReturn(Mono.error(writeError(1)));
        ConnectionMongoRepositoryAdapter adapter = new ConnectionMongoRepositoryAdapter(client, "mongodb://localhost:27017");

        StepVerifier.create(adapter.create(connection)).expectError(DuplicateConnectionException.class).verify();
        StepVerifier.create(adapter.create(connection)).expectError(MongoWriteException.class).verify();
    }

    @Test
    @DisplayName("connections: save is a guarded write on organisation and status; a lost race is a conflict; a rename onto a taken name is a duplicate")
    void connectionSave() {
        Connection connection = connection();
        var entry = connection.disable("op");
        when(connections.updateOne(any(Bson.class), any(Bson.class)))
                .thenReturn(Mono.just(UpdateResult.acknowledged(1, 1L, null)))
                .thenReturn(Mono.just(UpdateResult.acknowledged(0, 0L, null)))
                .thenReturn(Mono.error(writeError(11000)))
                .thenReturn(Mono.error(writeError(2)));
        ConnectionMongoRepositoryAdapter adapter = new ConnectionMongoRepositoryAdapter(client, URI);

        StepVerifier.create(adapter.save(connection, ConnectionStatus.ACTIVE, entry)).verifyComplete();
        StepVerifier.create(adapter.save(connection, ConnectionStatus.ACTIVE, entry)).expectError(InvalidConnectionStatusException.class).verify();
        StepVerifier.create(adapter.save(connection, ConnectionStatus.ACTIVE, entry)).expectError(DuplicateConnectionException.class).verify();
        StepVerifier.create(adapter.save(connection, ConnectionStatus.ACTIVE, entry)).expectError(MongoWriteException.class).verify();

        ArgumentCaptor<Bson> guard = ArgumentCaptor.forClass(Bson.class);
        verify(connections, times(4)).updateOne(guard.capture(), any(Bson.class));
        assertTrue(guard.getAllValues().get(0).toString().contains("organisationId") && guard.getAllValues().get(0).toString().contains("ACTIVE"));
    }

    // ------------------------------------------------------------------ Ticket links

    @Test
    @DisplayName("links: create, lookups scoped to the organisation, by provider ticket, and the filters of the list")
    void linkLookups() {
        TicketLink link = link();
        when(links.insertOne(any(TicketLinkDocument.class))).thenReturn(Mono.just(InsertOneResult.acknowledged(new BsonObjectId(new ObjectId()))));
        finds(links, found -> TicketLinkPersistenceMapper.toDocument((TicketLink) found), link);
        TicketLinkMongoRepositoryAdapter adapter = new TicketLinkMongoRepositoryAdapter(client, "mongodb://localhost:27017");

        StepVerifier.create(adapter.create(link)).expectNext(link).verifyComplete();
        StepVerifier.create(adapter.findById(link.getId(), org)).assertNext(found -> assertEquals(link.getId(), found.getId())).verifyComplete();
        StepVerifier.create(adapter.findByExternalId(link.getConnectionId(), "ITSM-1", org)).expectNextCount(1).verifyComplete();
        StepVerifier.create(adapter.findAll(org, new Filter(UUID.randomUUID(), SubjectType.INCIDENT, UUID.randomUUID(), LinkStatus.LINKED))).expectNextCount(1).verifyComplete();
        StepVerifier.create(adapter.findAll(org, new Filter(null, null, null, null))).expectNextCount(1).verifyComplete();

        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        verify(links, times(4)).find(filter.capture());
        String external = filter.getAllValues().get(1).toString();
        assertTrue(external.contains("externalId") && external.contains("connectionId") && external.contains("organisationId"));
        String full = filter.getAllValues().get(2).toString();
        assertTrue(full.contains("connectionId") && full.contains("subjectType") && full.contains("subjectId") && full.contains("LINKED"));
        String bare = filter.getAllValues().get(3).toString();
        assertTrue(bare.contains("organisationId") && !bare.contains("subjectType") && !bare.contains("status"));
    }

    @Test
    @DisplayName("links: a second link of the same item or ticket is a 409 of the domain; another write error passes through")
    void linkDuplicates() {
        TicketLink link = link();
        when(links.insertOne(any(TicketLinkDocument.class))).thenReturn(Mono.error(writeError(11000))).thenReturn(Mono.error(writeError(1)));
        TicketLinkMongoRepositoryAdapter adapter = new TicketLinkMongoRepositoryAdapter(client, URI);

        StepVerifier.create(adapter.create(link)).expectError(DuplicateTicketLinkException.class).verify();
        StepVerifier.create(adapter.create(link)).expectError(MongoWriteException.class).verify();
    }

    @Test
    @DisplayName("links: save is a guarded write on organisation and status; a lost race is a conflict; a clash on the ticket is a duplicate")
    void linkSave() {
        TicketLink link = link();
        var entry = link.recordPush("NEW", java.util.Set.of(), true, "op");
        when(links.updateOne(any(Bson.class), any(Bson.class)))
                .thenReturn(Mono.just(UpdateResult.acknowledged(1, 1L, null)))
                .thenReturn(Mono.just(UpdateResult.acknowledged(0, 0L, null)))
                .thenReturn(Mono.error(writeError(11000)))
                .thenReturn(Mono.error(writeError(2)));
        TicketLinkMongoRepositoryAdapter adapter = new TicketLinkMongoRepositoryAdapter(client, URI);

        StepVerifier.create(adapter.save(link, LinkStatus.LINKED, entry)).verifyComplete();
        StepVerifier.create(adapter.save(link, LinkStatus.LINKED, entry)).expectError(InvalidTicketLinkStatusException.class).verify();
        StepVerifier.create(adapter.save(link, LinkStatus.LINKED, entry)).expectError(DuplicateTicketLinkException.class).verify();
        StepVerifier.create(adapter.save(link, LinkStatus.LINKED, entry)).expectError(MongoWriteException.class).verify();

        ArgumentCaptor<Bson> guard = ArgumentCaptor.forClass(Bson.class);
        verify(links, times(4)).updateOne(guard.capture(), any(Bson.class));
        assertTrue(guard.getAllValues().get(0).toString().contains("organisationId") && guard.getAllValues().get(0).toString().contains("LINKED"));
    }

    // ------------------------------------------------------------------ Processed events

    @Test
    @DisplayName("events: the insert that succeeds is the first delivery; a duplicate key is a repeat; any other failure is an error")
    void processedEvents() {
        when(events.insertOne(any(Document.class)))
                .thenReturn(Mono.just(InsertOneResult.acknowledged(new BsonObjectId(new ObjectId()))))
                .thenReturn(Mono.error(writeError(11000)))
                .thenReturn(Mono.error(writeError(1)));
        ProcessedEventMongoRepositoryAdapter adapter = new ProcessedEventMongoRepositoryAdapter(client, URI);
        UUID connectionId = UUID.randomUUID();

        StepVerifier.create(adapter.markProcessed(connectionId, "evt-1")).expectNext(true).verifyComplete();
        StepVerifier.create(adapter.markProcessed(connectionId, "evt-1")).expectNext(false).verifyComplete();
        StepVerifier.create(adapter.markProcessed(connectionId, "evt-1")).expectError(MongoWriteException.class).verify();

        ArgumentCaptor<Document> entry = ArgumentCaptor.forClass(Document.class);
        verify(events, times(3)).insertOne(entry.capture());
        assertEquals(connectionId, entry.getAllValues().get(0).get("connectionId"));
        assertEquals("evt-1", entry.getAllValues().get(0).get("eventId"));
        assertTrue(entry.getAllValues().get(0).containsKey("processedAt"));
    }

    @Test
    @DisplayName("events: the database falls back to the service default when the URI has none")
    void processedEventsDefaultDatabase() {
        when(events.insertOne(any(Document.class))).thenReturn(Mono.just(InsertOneResult.acknowledged(new BsonObjectId(new ObjectId()))));

        StepVerifier.create(new ProcessedEventMongoRepositoryAdapter(client, "mongodb://localhost:27017").markProcessed(UUID.randomUUID(), "e")).expectNext(true).verifyComplete();
    }
}
