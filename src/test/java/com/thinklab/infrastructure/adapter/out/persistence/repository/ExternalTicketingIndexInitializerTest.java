package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.MongoTimeoutException;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import io.micronaut.context.event.StartupEvent;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class ExternalTicketingIndexInitializerTest {

    private final StartupEvent startup = mock(StartupEvent.class);

    private MongoCollection<Document> collectionIn(MongoDatabase database, String name) {
        MongoCollection<Document> collection = mock(MongoCollection.class);
        when(database.getCollection(name)).thenReturn(collection);
        when(collection.createIndex(any(), any(IndexOptions.class))).thenReturn(Mono.just("ok"));
        return collection;
    }

    @Test
    @DisplayName("startup creates the unique backstops and the TTL index, each on the collection its query uses")
    void createsIndexes() {
        MongoClient client = mock(MongoClient.class);
        MongoDatabase database = mock(MongoDatabase.class);
        when(client.getDatabase("tenant_etk")).thenReturn(database);
        MongoCollection<Document> connections = collectionIn(database, "connections");
        MongoCollection<Document> links = collectionIn(database, "ticket_links");
        MongoCollection<Document> events = collectionIn(database, "processed_events");

        new ExternalTicketingIndexInitializer(client, "mongodb://mongo:27017/tenant_etk").onApplicationEvent(startup);

        ArgumentCaptor<IndexOptions> connectionOptions = ArgumentCaptor.forClass(IndexOptions.class);
        verify(connections).createIndex(any(), connectionOptions.capture());
        assertTrue(connectionOptions.getValue().isUnique());
        assertEquals(ExternalTicketingIndexInitializer.CONNECTION_NAME_INDEX, connectionOptions.getValue().getName());

        ArgumentCaptor<Document> linkKeys = ArgumentCaptor.forClass(Document.class);
        ArgumentCaptor<IndexOptions> linkOptions = ArgumentCaptor.forClass(IndexOptions.class);
        verify(links, times(3)).createIndex(linkKeys.capture(), linkOptions.capture());
        assertEquals(List.of(ExternalTicketingIndexInitializer.LINK_SUBJECT_INDEX, ExternalTicketingIndexInitializer.LINK_EXTERNAL_INDEX,
                ExternalTicketingIndexInitializer.LINK_STATUS_INDEX), linkOptions.getAllValues().stream().map(IndexOptions::getName).toList());
        assertTrue(linkOptions.getAllValues().get(0).isUnique());
        assertTrue(linkOptions.getAllValues().get(1).isUnique());
        assertNotNull(linkOptions.getAllValues().get(1).getPartialFilterExpression());
        assertEquals(false, linkOptions.getAllValues().get(2).isUnique());

        ArgumentCaptor<IndexOptions> eventOptions = ArgumentCaptor.forClass(IndexOptions.class);
        verify(events, times(2)).createIndex(any(), eventOptions.capture());
        assertTrue(eventOptions.getAllValues().get(0).isUnique());
        assertEquals(ExternalTicketingIndexInitializer.EVENT_TTL_DAYS, eventOptions.getAllValues().get(1).getExpireAfter(TimeUnit.DAYS));
    }

    @Test
    @DisplayName("a URI without a database uses the service default")
    void defaultDatabase() {
        MongoClient client = mock(MongoClient.class);
        MongoDatabase database = mock(MongoDatabase.class);
        when(client.getDatabase(ConnectionMongoRepositoryAdapter.DEFAULT_DATABASE)).thenReturn(database);
        MongoCollection<Document> connections = collectionIn(database, "connections");
        collectionIn(database, "ticket_links");
        collectionIn(database, "processed_events");

        new ExternalTicketingIndexInitializer(client, "mongodb://mongo:27017").onApplicationEvent(startup);

        verify(connections).createIndex(any(), any(IndexOptions.class));
    }

    @Test
    @DisplayName("fail-open: an unreachable server or a rejected index is logged, never propagated")
    void failOpen() {
        MongoClient client = mock(MongoClient.class);
        MongoDatabase database = mock(MongoDatabase.class);
        when(client.getDatabase("etk")).thenReturn(database);
        MongoCollection<Document> connections = mock(MongoCollection.class);
        when(database.getCollection("connections")).thenReturn(connections);
        when(connections.createIndex(any(), any(IndexOptions.class))).thenReturn(Mono.error(new MongoTimeoutException("no server")));
        MongoCollection<Document> links = mock(MongoCollection.class);
        when(database.getCollection("ticket_links")).thenReturn(links);
        when(links.createIndex(any(), any(IndexOptions.class))).thenReturn(Mono.error(new IllegalStateException("rejected"))).thenReturn(Mono.just("ok"));
        collectionIn(database, "processed_events");

        assertDoesNotThrow(() -> new ExternalTicketingIndexInitializer(client, "mongodb://mongo:27017/etk", Duration.ofSeconds(1)).onApplicationEvent(startup));
        verify(links, times(3)).createIndex(any(), any(IndexOptions.class));
    }

    @Test
    @DisplayName("collaborators, mongodb.uri and the startup event are null-checked")
    void guards() {
        MongoClient client = mock(MongoClient.class);
        assertThrows(NullPointerException.class, () -> new ExternalTicketingIndexInitializer(null, "mongodb://mongo:27017/a"));
        assertThrows(NullPointerException.class, () -> new ExternalTicketingIndexInitializer(client, null));
        assertThrows(NullPointerException.class, () -> new ExternalTicketingIndexInitializer(client, "mongodb://mongo:27017/a").onApplicationEvent(null));
    }
}
