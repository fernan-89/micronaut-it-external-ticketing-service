package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.thinklab.domain.exception.DuplicateConnectionException;
import com.thinklab.domain.exception.InvalidConnectionStatusException;
import com.thinklab.domain.model.Connection;
import com.thinklab.domain.model.Connection.ConnectionAuditEntry;
import com.thinklab.domain.model.Connection.ConnectionStatus;
import com.thinklab.domain.repository.ConnectionRepository;
import com.thinklab.infrastructure.adapter.out.persistence.entity.ConnectionDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.ConnectionDocument.AuditEntryDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.ConnectionDocument.ConnectionPersistenceMapper;
import io.micronaut.context.annotation.Property;
import jakarta.inject.Singleton;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * MongoDB Reactive Repository Adapter for the Connection aggregate, raw reactive-streams driver. Every change is a single atomic
 * {@code $set}/{@code $push} that also appends the audit entry, guarded by the status the connection was loaded with (ADR-033); every
 * lookup carries the organisation, except {@link #findByIdAnyTenant}, which the webhook needs.
 */
@Singleton
public class ConnectionMongoRepositoryAdapter implements ConnectionRepository {

    private static final Logger log = LoggerFactory.getLogger(ConnectionMongoRepositoryAdapter.class);
    static final String DEFAULT_DATABASE = "thinklab_it_external_ticketing_db";
    static final String COLLECTION_NAME = "connections";
    static final int DUPLICATE_KEY = 11000;
    private static final String FIELD_ID = "_id";
    private static final String FIELD_ORGANISATION = "organisationId";
    private static final String FIELD_STATUS = "status";

    private static final CodecRegistry POJO_CODEC_REGISTRY = CodecRegistries.fromRegistries(
            MongoClientSettings.getDefaultCodecRegistry(),
            CodecRegistries.fromProviders(PojoCodecProvider.builder().automatic(true).build())
    );

    private final MongoClient mongoClient;
    private final String database;

    public ConnectionMongoRepositoryAdapter(MongoClient mongoClient, @Property(name = "mongodb.uri") String mongoUri) {
        this.mongoClient = mongoClient;
        String configured = new ConnectionString(Objects.requireNonNull(mongoUri, "mongodb.uri cannot be null.")).getDatabase();
        this.database = configured != null ? configured : DEFAULT_DATABASE;
    }

    private MongoCollection<ConnectionDocument> getCollection() {
        return mongoClient.getDatabase(database).getCollection(COLLECTION_NAME, ConnectionDocument.class).withCodecRegistry(POJO_CODEC_REGISTRY);
    }

    @Override
    public Mono<Connection> create(Connection connection) {
        log.debug("[PERSISTENCE] Monolithic create for Connection Aggregate: {}", connection.getId());

        return Mono.from(getCollection().insertOne(ConnectionPersistenceMapper.toDocument(connection)))
                .map(result -> connection)
                .onErrorMap(MongoWriteException.class, error -> error.getError().getCode() == DUPLICATE_KEY
                        ? new DuplicateConnectionException("A connection named '" + connection.getName() + "' already exists in this organisation.") : error);
    }

    @Override
    public Mono<Connection> findById(UUID id, UUID organisationId) {
        return Mono.from(getCollection().find(Filters.and(Filters.eq(FIELD_ID, id), Filters.eq(FIELD_ORGANISATION, organisationId))).first())
                .map(ConnectionPersistenceMapper::toDomain);
    }

    @Override
    public Mono<Connection> findByIdAnyTenant(UUID id) {
        return Mono.from(getCollection().find(Filters.eq(FIELD_ID, id)).first()).map(ConnectionPersistenceMapper::toDomain);
    }

    @Override
    public Mono<Boolean> existsByName(String name, UUID organisationId) {
        return Mono.from(getCollection().countDocuments(Filters.and(Filters.eq(FIELD_ORGANISATION, organisationId), Filters.eq("name", name))))
                .map(count -> count > 0);
    }

    @Override
    public Flux<Connection> findAll(UUID organisationId) {
        return Flux.from(getCollection().find(Filters.eq(FIELD_ORGANISATION, organisationId))).map(ConnectionPersistenceMapper::toDomain);
    }

    @Override
    public Mono<Void> save(Connection connection, ConnectionStatus expectedStatus, ConnectionAuditEntry auditEntry) {
        Bson guard = Filters.and(Filters.eq(FIELD_ID, connection.getId()), Filters.eq(FIELD_ORGANISATION, connection.getOrganisationId()),
                Filters.eq(FIELD_STATUS, expectedStatus.name()));
        ConnectionDocument state = ConnectionPersistenceMapper.toDocument(connection);
        Bson update = Updates.combine(
                Updates.set("name", state.getName()),
                Updates.set("baseUrl", state.getBaseUrl()),
                Updates.set("secretRef", state.getSecretRef()),
                Updates.set("webhookSecretRef", state.getWebhookSecretRef()),
                Updates.set("integrationActor", state.getIntegrationActor()),
                Updates.set("projectKey", state.getProjectKey()),
                Updates.set("outboundStatus", state.getOutboundStatus()),
                Updates.set("inboundActions", state.getInboundActions()),
                Updates.set(FIELD_STATUS, state.getStatus()),
                Updates.set("updatedAt", Instant.now()),
                Updates.push("auditTrail", AuditEntryDocument.fromDomain(auditEntry))
        );
        return Mono.from(getCollection().updateOne(guard, update))
                .flatMap(result -> result.getMatchedCount() == 0
                        ? Mono.error(new InvalidConnectionStatusException("Connection was changed by someone else while this change was being recorded; read it again and retry."))
                        : Mono.<Void>empty())
                .onErrorMap(MongoWriteException.class, error -> error.getError().getCode() == DUPLICATE_KEY
                        ? new DuplicateConnectionException("A connection named '" + connection.getName() + "' already exists in this organisation.") : error);
    }
}
