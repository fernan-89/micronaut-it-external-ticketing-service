package com.thinklab.application.dto.request;

import com.thinklab.domain.model.Connection.InboundAction;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/** DTO for {@code connection/update}: everything but the provider. Maps left out go back to the provider defaults. */
@Serdeable
public record UpdateConnectionRequest(
        @NotBlank(message = "Name is required")
        @Size(max = 80, message = "Name must not exceed 80 characters")
        String name,
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
