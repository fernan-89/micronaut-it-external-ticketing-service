package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.ConnectionString;
import com.mongodb.MongoWriteException;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.thinklab.domain.repository.ProcessedEventRepository;
import io.micronaut.context.annotation.Property;
import jakarta.inject.Singleton;
import org.bson.Document;
import reactor.core.publisher.Mono;

import java.util.Date;
import java.util.Objects;
import java.util.UUID;

/**
 * Makes the webhook idempotent with an insert against a unique index on {@code (connectionId, eventId)}: the insert that succeeds is the
 * first delivery, a duplicate-key failure is a repeat. Entries carry {@code processedAt}, which a TTL index expires.
 */
@Singleton
public class ProcessedEventMongoRepositoryAdapter implements ProcessedEventRepository {

    static final String COLLECTION_NAME = "processed_events";

    private final MongoClient mongoClient;
    private final String database;

    public ProcessedEventMongoRepositoryAdapter(MongoClient mongoClient, @Property(name = "mongodb.uri") String mongoUri) {
        this.mongoClient = mongoClient;
        String configured = new ConnectionString(Objects.requireNonNull(mongoUri, "mongodb.uri cannot be null.")).getDatabase();
        this.database = configured != null ? configured : ConnectionMongoRepositoryAdapter.DEFAULT_DATABASE;
    }

    private MongoCollection<Document> getCollection() {
        return mongoClient.getDatabase(database).getCollection(COLLECTION_NAME);
    }

    @Override
    public Mono<Boolean> markProcessed(UUID connectionId, String eventId) {
        Document entry = new Document("connectionId", connectionId).append("eventId", eventId).append("processedAt", new Date());
        return Mono.from(getCollection().insertOne(entry))
                .thenReturn(true)
                .onErrorResume(MongoWriteException.class, error -> error.getError().getCode() == ConnectionMongoRepositoryAdapter.DUPLICATE_KEY
                        ? Mono.just(false) : Mono.error(error));
    }
}
