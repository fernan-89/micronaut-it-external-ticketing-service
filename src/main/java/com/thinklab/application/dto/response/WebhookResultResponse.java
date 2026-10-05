package com.thinklab.application.dto.response;

import io.micronaut.serde.annotation.Serdeable;

/**
 * What an inbound webhook did, so the provider (and whoever reads its delivery log) can see it. Always answered 200 once the token is
 * right, even when nothing was applied: a provider retries on an error, and an event the platform deliberately ignored must not be retried.
 */
@Serdeable
public record WebhookResultResponse(String outcome, String detail) {
    public static final String APPLIED = "APPLIED";
    public static final String DUPLICATE = "DUPLICATE";
    public static final String IGNORED_OWN_EVENT = "IGNORED_OWN_EVENT";
    public static final String IGNORED_UNKNOWN_TICKET = "IGNORED_UNKNOWN_TICKET";
    public static final String NOTHING_TO_APPLY = "NOTHING_TO_APPLY";
    public static final String CONFLICT_PLATFORM_WINS = "CONFLICT_PLATFORM_WINS";
}
