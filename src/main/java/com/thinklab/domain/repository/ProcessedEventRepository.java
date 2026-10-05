package com.thinklab.domain.repository;

import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Outbound Port that makes the inbound webhook idempotent: a provider may deliver the same event twice, and the platform must apply it
 * once. Entries expire by themselves (a TTL index), so the collection does not grow forever.
 */
public interface ProcessedEventRepository {

    /** Records the event; {@code true} if it is new, {@code false} if it had already been processed. */
    Mono<Boolean> markProcessed(UUID connectionId, String eventId);
}
