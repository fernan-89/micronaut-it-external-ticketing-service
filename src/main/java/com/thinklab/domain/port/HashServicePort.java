package com.thinklab.domain.port;

import reactor.core.publisher.Mono;
import java.util.UUID;

/**
 * Outbound Port for Sovereign Identity and Cryptographic operations.
 * Part of the pure Domain/Application Layer.
 *
 * ARCHITECTURAL RULE: Sovereign Identity.
 * The application must never generate its own IDs or rely on database sequences (e.g., MongoDB ObjectId).
 * All primary identifiers must be fetched via this port, which integrates with the external Hash-Service
 * utilizing Blake3 algorithms to ensure global uniqueness and cryptographic safety.
 */
public interface HashServicePort {

    /**
     * Requests a new cryptographically secure Sovereign Identity (UUID v4) from the Hash-Service.
     *
     * @param context A descriptive context for the ID generation (e.g., "user-creation", "ticketLink-creation").
     * @return A Mono emitting the newly generated UUID v4.
     */
    Mono<UUID> generateSovereignId(String context);

    /**
     * Requests a cryptographically secure hash for sensitive string data.
     * Useful for MFA secrets or API keys associated with the service's own aggregates.
     *
     * @param rawData The raw string to be hashed.
     * @return A Mono emitting the hashed string.
     */
    Mono<String> hashSensitiveData(String rawData);
}