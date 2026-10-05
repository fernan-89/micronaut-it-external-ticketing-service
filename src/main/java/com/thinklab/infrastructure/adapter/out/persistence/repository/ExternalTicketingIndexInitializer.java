package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.ConnectionString;
import com.mongodb.MongoTimeoutException;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.reactivestreams.client.MongoClient;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Creates the indexes at startup. The UNIQUE ones are not an optimisation but the atomic backstop of the rules (ADR-033): a connection name
 * per organisation; one link per item and connection; one link per provider ticket; one processed webhook event per connection (which also
 * expires, so the collection does not grow for ever). The adapters use the driver directly, so the kit's generic initializer does not see them.
 *
 * <p>Fail-open: {@code createIndex} is idempotent; a failure is logged and the application still starts. Turn it off with
 * {@code thinklab.mongo.create-indexes=false}.
 */
@Singleton
@Requires(property = "thinklab.mongo.create-indexes", notEquals = "false")
public class ExternalTicketingIndexInitializer implements ApplicationEventListener<StartupEvent> {

    static final String CONNECTION_NAME_INDEX = "organisationId_1_name_1";
    static final String LINK_SUBJECT_INDEX = "organisationId_1_connectionId_1_subjectType_1_subjectId_1";
    static final String LINK_EXTERNAL_INDEX = "organisationId_1_connectionId_1_externalId_1";
    static final String LINK_STATUS_INDEX = "organisationId_1_status_1";
    static final String EVENT_INDEX = "connectionId_1_eventId_1";
    static final String EVENT_TTL_INDEX = "processedAt_ttl";
    static final long EVENT_TTL_DAYS = 30;

    private static final Logger log = LoggerFactory.getLogger(ExternalTicketingIndexInitializer.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final MongoClient mongoClient;
    private final String database;
    private final Duration timeout;

    @Inject
    public ExternalTicketingIndexInitializer(MongoClient mongoClient, @Property(name = "mongodb.uri") String mongoUri) {
        this(mongoClient, mongoUri, TIMEOUT);
    }

    ExternalTicketingIndexInitializer(MongoClient mongoClient, String mongoUri, Duration timeout) {
        this.mongoClient = Objects.requireNonNull(mongoClient, "Infrastructure constraint violated: MongoClient cannot be null.");
        String configured = new ConnectionString(Objects.requireNonNull(mongoUri, "mongodb.uri cannot be null.")).getDatabase();
        this.database = configured != null ? configured : ConnectionMongoRepositoryAdapter.DEFAULT_DATABASE;
        this.timeout = timeout;
    }

    @Override
    public void onApplicationEvent(StartupEvent event) {
        Objects.requireNonNull(event, "Application constraint violated: StartupEvent cannot be null.");
        ensureIndex(ConnectionMongoRepositoryAdapter.COLLECTION_NAME, CONNECTION_NAME_INDEX, new Document("organisationId", 1).append("name", 1),
                new IndexOptions().unique(true));
        ensureIndex(TicketLinkMongoRepositoryAdapter.COLLECTION_NAME, LINK_SUBJECT_INDEX,
                new Document("organisationId", 1).append("connectionId", 1).append("subjectType", 1).append("subjectId", 1), new IndexOptions().unique(true));
        // Partial: a link that has no ticket yet has no externalId, and must not collide with another such link.
        ensureIndex(TicketLinkMongoRepositoryAdapter.COLLECTION_NAME, LINK_EXTERNAL_INDEX,
                new Document("organisationId", 1).append("connectionId", 1).append("externalId", 1),
                new IndexOptions().unique(true).partialFilterExpression(new Document("externalId", new Document("$type", "string"))));
        ensureIndex(TicketLinkMongoRepositoryAdapter.COLLECTION_NAME, LINK_STATUS_INDEX, new Document("organisationId", 1).append("status", 1), new IndexOptions());
        ensureIndex(ProcessedEventMongoRepositoryAdapter.COLLECTION_NAME, EVENT_INDEX, new Document("connectionId", 1).append("eventId", 1),
                new IndexOptions().unique(true));
        ensureIndex(ProcessedEventMongoRepositoryAdapter.COLLECTION_NAME, EVENT_TTL_INDEX, new Document("processedAt", 1),
                new IndexOptions().expireAfter(EVENT_TTL_DAYS, TimeUnit.DAYS));
    }

    private void ensureIndex(String collection, String indexName, Document keys, IndexOptions options) {
        try {
            Mono.from(mongoClient.getDatabase(database).getCollection(collection).createIndex(keys, options.name(indexName))).block(timeout);
            log.info("[MONGO_INDEXES] Ensured index [{}] on [{}.{}]", indexName, database, collection);
        } catch (MongoTimeoutException e) {
            log.error("[MONGO_INDEXES] MongoDB unreachable; index [{}] was not created. Reason: {}", indexName, e.getMessage());
        } catch (RuntimeException e) {
            log.error("[MONGO_INDEXES] Could not create index [{}] on [{}.{}]: {}", indexName, database, collection, e.getMessage());
        }
    }
}
