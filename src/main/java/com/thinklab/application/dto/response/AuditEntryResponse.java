package com.thinklab.application.dto.response;

import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;

@Serdeable
public record AuditEntryResponse(Instant occurredAt, String action, String executor, String fromStatus, String toStatus, String detail) {}
