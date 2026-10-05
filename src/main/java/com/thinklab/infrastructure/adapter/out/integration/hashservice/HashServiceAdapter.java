package com.thinklab.infrastructure.adapter.out.integration.hashservice;

import com.thinklab.domain.port.HashServicePort;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Outbound Adapter for the external Hash Token Registry Service Domain.
 * Implements the Domain Port, ensuring that Micronaut-specific HTTP client
 * details do not leak into the Application or Domain layers.
 *
 * <p><b>BIAN Alignment (ADR-013):</b> Calls the Hash Token Registry's {@code initiate} Behavior
 * Qualifier ({@code POST /hash-token-registry/v1/initiate}) with the {@code X-Tenant-Id},
 * {@code X-Source-Service}, and {@code X-Executor} headers the Hash Service now mandates, and
 * derives the Sovereign ID / hashed value from the returned Control Record ({@code id} /
 * {@code generatedHash}).
 */
@Singleton
public class HashServiceAdapter implements HashServicePort {

    private static final Logger log = LoggerFactory.getLogger(HashServiceAdapter.class);

    private static final String TENANT_ID = "it-external-ticketing";
    private static final String SOURCE_SERVICE = "it-external-ticketing-service";
    private static final String SYSTEM_EXECUTOR = "system";
    private static final String ALGORITHM = "SHA3_512";

    private final HashApiClient apiClient;

    public HashServiceAdapter(HashApiClient apiClient) {
        this.apiClient = apiClient;
    }

    @Override
    public Mono<UUID> generateSovereignId(String context) {
        log.debug("[INTEGRATION] Requesting secure Sovereign ID from Hash Token Registry. Context: {}", context);

        String payload = context + "::" + UUID.randomUUID();

        return apiClient.initiate(TENANT_ID, SOURCE_SERVICE, SYSTEM_EXECUTOR, new InitiateHashRequest(payload, ALGORITHM, false))
                .map(HashTokenResponse::id)
                .doOnSuccess(id -> log.debug("[INTEGRATION] Successfully retrieved Sovereign ID: {}", id))
                .doOnError(error -> log.error("[INTEGRATION FAILURE] Failed to fetch Sovereign ID from Hash Token Registry", error))
                // SRE: Translating infrastructure failures to prevent internal stack traces from leaking
                .onErrorMap(error -> new IllegalStateException("Dependency Failure: Hash Token Registry Service is currently unavailable", error));
    }

    @Override
    public Mono<String> hashSensitiveData(String rawData) {
        log.debug("[INTEGRATION] Requesting cryptographic hash for sensitive data from Hash Token Registry.");

        return apiClient.initiate(TENANT_ID, SOURCE_SERVICE, SYSTEM_EXECUTOR, new InitiateHashRequest(rawData, ALGORITHM, false))
                .map(HashTokenResponse::generatedHash)
                .doOnError(error -> log.error("[INTEGRATION FAILURE] Failed to hash sensitive data", error))
                .onErrorMap(error -> new IllegalStateException("Dependency Failure: Hash Token Registry cryptographic operations are unavailable", error));
    }

    @Serdeable
    @Introspected
    record InitiateHashRequest(String payload, String algorithm, boolean asSerialKey) {}

    @Serdeable
    @Introspected
    record HashTokenResponse(UUID id, String generatedHash) {}
}

/**
 * Declarative Micronaut HTTP Client for the Hash Token Registry Service Domain.
 * Package-private visibility strictly encapsulates this integration detail within the adapter.
 * The 'id' maps to the configuration in application.yml for dynamic resolution.
 */
@Client(id = "hash-service", path = "/hash-token-registry/v1")
interface HashApiClient {

    @Post("/initiate")
    Mono<HashServiceAdapter.HashTokenResponse> initiate(
            @Header("X-Tenant-Id") String tenantId,
            @Header("X-Source-Service") String sourceService,
            @Header("X-Executor") String executor,
            @Body HashServiceAdapter.InitiateHashRequest request
    );
}
