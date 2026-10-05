package com.thinklab.application.usecase;

import com.thinklab.application.config.ExternalTicketingProperties;
import jakarta.inject.Singleton;

import java.net.URI;
import java.util.Locale;

/**
 * Where a connection may point (ADR-032). The address is an operator's input and the service calls it with a credential, so it must be
 * https and must not be a loopback, link-local or private address given as a literal; a host listed in
 * {@code thinklab.external-ticketing.insecure-hosts} (a test double) is exempt. A name that resolves to a private address is not caught
 * here (that is a network egress rule); this refuses the plain mistakes and the obvious abuses.
 */
@Singleton
public class BaseUrlPolicy {

    private final ExternalTicketingProperties properties;

    public BaseUrlPolicy(ExternalTicketingProperties properties) {
        this.properties = properties;
    }

    public void check(String baseUrl) {
        URI uri = URI.create(baseUrl.trim());
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (properties.getInsecureHosts().stream().anyMatch(allowed -> allowed.equalsIgnoreCase(host))) {
            return;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("The base URL must be https.");
        }
        if (isPrivateLiteral(host)) {
            throw new IllegalArgumentException("The base URL must not be a loopback, link-local or private address.");
        }
    }

    private static boolean isPrivateLiteral(String host) {
        if (host.equals("localhost") || host.equals("[::1]") || host.startsWith("[fe80") || host.startsWith("[fc") || host.startsWith("[fd")) {
            return true;
        }
        if (host.matches("[0-9]+")) {
            return true;
        }
        if (!host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) {
            return false;
        }
        String[] parts = host.split("\\.");
        int first = Integer.parseInt(parts[0]);
        int second = Integer.parseInt(parts[1]);
        return first == 10 || first == 127 || first == 0 || (first == 169 && second == 254) || (first == 172 && second >= 16 && second <= 31) || (first == 192 && second == 168);
    }
}
