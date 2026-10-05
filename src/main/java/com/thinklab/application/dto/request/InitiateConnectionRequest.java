package com.thinklab.application.dto.request;

import com.thinklab.domain.model.Connection.InboundAction;
import com.thinklab.domain.model.Connection.Provider;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * DTO for Connection creation (BIAN Behavior Qualifier: {@code connection/initiate}). {@code secretRef} and {@code webhookSecretRef} are
 * the NAMES of environment variables, never the secrets (ADR-032). The two maps start from the provider defaults when left out.
 */
@Serdeable
public record InitiateConnectionRequest(
        @NotBlank(message = "Name is required")
        @Size(max = 80, message = "Name must not exceed 80 characters")
        String name,
        @NotNull(message = "Provider is required")
        Provider provider,
        @NotBlank(message = "Base URL is required")
        @Size(max = 300, message = "Base URL must not exceed 300 characters")
        String baseUrl,
        @NotBlank(message = "The name of the credentials variable is required")
        String secretRef,
        @NotBlank(message = "The name of the webhook token variable is required")
        String webhookSecretRef,
        @NotBlank(message = "The integration actor is required")
        @Size(max = 200, message = "Integration actor must not exceed 200 characters")
        String integrationActor,
        @Nullable
        String projectKey,
        @Nullable
        Map<String, String> outboundStatus,
        @Nullable
        Map<String, InboundAction> inboundActions
) {}
