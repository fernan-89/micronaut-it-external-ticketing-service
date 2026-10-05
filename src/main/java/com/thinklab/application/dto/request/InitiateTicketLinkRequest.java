package com.thinklab.application.dto.request;

import com.thinklab.domain.model.TicketLink.SubjectType;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** DTO for linking a platform item to a provider ticket (BIAN Behavior Qualifier: {@code initiate}): the ticket is created at the provider. */
@Serdeable
public record InitiateTicketLinkRequest(
        @NotNull(message = "Connection is required")
        UUID connectionId,
        @NotNull(message = "Subject type is required")
        SubjectType subjectType,
        @NotNull(message = "Subject is required")
        UUID subjectId
) {}
