package com.thinklab.domain.port;

/**
 * Outbound Port that turns the NAME of a secret into its value, at the moment it is used (ADR-032). The value never goes anywhere but
 * into the request it was needed for.
 */
public interface SecretResolverPort {

    /** The value of the secret, or {@code null} when it is not configured in this environment. */
    String resolve(String name);
}
