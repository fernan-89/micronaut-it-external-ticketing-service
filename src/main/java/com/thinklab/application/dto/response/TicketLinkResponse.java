package com.thinklab.application.dto.response;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;
import java.util.UUID;

@Serdeable
public record TicketLinkResponse(
        UUID id,
        UUID organisationId,
        UUID connectionId,
        String subjectType,
        UUID subjectId,
        @Nullable String externalId,
        @Nullable String externalUrl,
        String status,
        @Nullable String lastPushedStatus,
        int syncedComments,
        @Nullable Instant lastSyncedAt,
        @Nullable String lastDirection,
        @Nullable String lastError,
        Instant createdAt,
        Instant updatedAt
) {}
