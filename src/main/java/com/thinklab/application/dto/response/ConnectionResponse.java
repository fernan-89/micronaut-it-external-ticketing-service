package com.thinklab.application.dto.response;

import com.thinklab.domain.model.Connection.InboundAction;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A connection as read. It names the variables that hold the secrets and says whether each one is configured in this environment; it
 * never carries a secret (ADR-032).
 */
@Serdeable
public record ConnectionResponse(
        UUID id,
        UUID organisationId,
        String name,
        String provider,
        String baseUrl,
        String secretRef,
        boolean secretConfigured,
        String webhookSecretRef,
        boolean webhookSecretConfigured,
        String integrationActor,
        @Nullable String projectKey,
        Map<String, String> outboundStatus,
        Map<String, InboundAction> inboundActions,
        String status,
        Instant createdAt,
        Instant updatedAt
) {}
