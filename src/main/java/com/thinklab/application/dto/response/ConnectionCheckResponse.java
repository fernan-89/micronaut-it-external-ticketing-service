package com.thinklab.application.dto.response;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/** The answer to "does this connection work": are both secrets configured here, and did the provider accept an authenticated call. */
@Serdeable
public record ConnectionCheckResponse(boolean secretConfigured, boolean webhookSecretConfigured, boolean reachable, @Nullable String problem) {}
