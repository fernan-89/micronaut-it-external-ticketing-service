package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.thinklab.domain.exception.DuplicateTicketLinkException;
import com.thinklab.domain.exception.InvalidTicketLinkStatusException;
import com.thinklab.domain.model.TicketLink;
import com.thinklab.domain.model.TicketLink.LinkAuditEntry;
import com.thinklab.domain.model.TicketLink.LinkStatus;
import com.thinklab.domain.repository.TicketLinkRepository;
import com.thinklab.infrastructure.adapter.out.persistence.entity.TicketLinkDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.TicketLinkDocument.AuditEntryDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.TicketLinkDocument.TicketLinkPersistenceMapper;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * MongoDB Reactive Repository Adapter for the TicketLink aggregate, raw reactive-streams driver. Every change is a single atomic update
 * that also appends the audit entry, guarded by the status the link was loaded with (ADR-033), and the unique indexes are the atomic
 * backstop: one link per item and connection, and one link per provider ticket.
 */
@Singleton
public class TicketLinkMongoRepositoryAdapter implements TicketLinkRepository {

    private static final Logger log = LoggerFactory.getLogger(TicketLinkMongoRepositoryAdapter.class);
    static final String COLLECTION_NAME = "ticket_links";
    private static final String FIELD_ID = "_id";
    private static final String FIELD_ORGANISATION = "organisationId";
    private static final String FIELD_STATUS = "status";

    private static final CodecRegistry POJO_CODEC_REGISTRY = CodecRegistries.fromRegistries(
            MongoClientSettings.getDefaultCodecRegistry(),
            CodecRegistries.fromProviders(PojoCodecProvider.builder().automatic(true).build())
    );

    private final MongoClient mongoClient;
    private final String database;

    public TicketLinkMongoRepositoryAdapter(MongoClient mongoClient, @Property(name = "mongodb.uri") String mongoUri) {
        this.mongoClient = mongoClient;
        String configured = new ConnectionString(Objects.requireNonNull(mongoUri, "mongodb.uri cannot be null.")).getDatabase();
        this.database = configured != null ? configured : ConnectionMongoRepositoryAdapter.DEFAULT_DATABASE;
    }

    private MongoCollection<TicketLinkDocument> getCollection() {
        return mongoClient.getDatabase(database).getCollection(COLLECTION_NAME, TicketLinkDocument.class).withCodecRegistry(POJO_CODEC_REGISTRY);
    }

    @Override
    public Mono<TicketLink> create(TicketLink link) {
        log.debug("[PERSISTENCE] Monolithic create for TicketLink Aggregate: {}", link.getId());

        return Mono.from(getCollection().insertOne(TicketLinkPersistenceMapper.toDocument(link)))
                .map(result -> link)
                .onErrorMap(MongoWriteException.class, error -> error.getError().getCode() == ConnectionMongoRepositoryAdapter.DUPLICATE_KEY
                        ? new DuplicateTicketLinkException("This " + link.getSubjectType() + " is already linked to that connection.") : error);
    }

    @Override
    public Mono<TicketLink> findById(UUID id, UUID organisationId) {
        return Mono.from(getCollection().find(Filters.and(Filters.eq(FIELD_ID, id), Filters.eq(FIELD_ORGANISATION, organisationId))).first())
                .map(TicketLinkPersistenceMapper::toDomain);
    }

    @Override
    public Mono<TicketLink> findByExternalId(UUID connectionId, String externalId, UUID organisationId) {
        return Mono.from(getCollection().find(Filters.and(Filters.eq(FIELD_ORGANISATION, organisationId), Filters.eq("connectionId", connectionId),
                Filters.eq("externalId", externalId))).first()).map(TicketLinkPersistenceMapper::toDomain);
    }

    @Override
    public Flux<TicketLink> findAll(UUID organisationId, Filter filter) {
        List<Bson> filters = new ArrayList<>();
        filters.add(Filters.eq(FIELD_ORGANISATION, organisationId));
        if (filter.connectionId() != null) {
            filters.add(Filters.eq("connectionId", filter.connectionId()));
        }
        if (filter.subjectType() != null) {
            filters.add(Filters.eq("subjectType", filter.subjectType().name()));
        }
        if (filter.subjectId() != null) {
            filters.add(Filters.eq("subjectId", filter.subjectId()));
        }
        if (filter.status() != null) {
            filters.add(Filters.eq(FIELD_STATUS, filter.status().name()));
        }
        return Flux.from(getCollection().find(Filters.and(filters))).map(TicketLinkPersistenceMapper::toDomain);
    }

    @Override
    public Mono<Void> save(TicketLink link, LinkStatus expectedStatus, LinkAuditEntry auditEntry) {
        Bson guard = Filters.and(Filters.eq(FIELD_ID, link.getId()), Filters.eq(FIELD_ORGANISATION, link.getOrganisationId()),
                Filters.eq(FIELD_STATUS, expectedStatus.name()));
        TicketLinkDocument state = TicketLinkPersistenceMapper.toDocument(link);
        Bson update = Updates.combine(
                Updates.set("externalId", state.getExternalId()),
                Updates.set("externalUrl", state.getExternalUrl()),
                Updates.set(FIELD_STATUS, state.getStatus()),
                Updates.set("lastPushedStatus", state.getLastPushedStatus()),
                Updates.set("syncedCommentIds", state.getSyncedCommentIds()),
                Updates.set("lastSyncedAt", state.getLastSyncedAt()),
                Updates.set("lastDirection", state.getLastDirection()),
                Updates.set("lastError", state.getLastError()),
                Updates.set("updatedAt", Instant.now()),
                Updates.push("auditTrail", AuditEntryDocument.fromDomain(auditEntry))
        );
        return Mono.from(getCollection().updateOne(guard, update))
                .flatMap(result -> result.getMatchedCount() == 0
                        ? Mono.error(new InvalidTicketLinkStatusException("TicketLink was changed by someone else while this change was being recorded; read it again and retry."))
                        : Mono.<Void>empty())
                .onErrorMap(MongoWriteException.class, error -> error.getError().getCode() == ConnectionMongoRepositoryAdapter.DUPLICATE_KEY
                        ? new DuplicateTicketLinkException("That provider ticket is already linked to another item.") : error);
    }
}
