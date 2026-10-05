package com.thinklab.infrastructure.adapter.out.integration.ticketing;

import com.thinklab.domain.exception.ExternalTicketingException;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * The one place that talks HTTP to a provider (ADR-032). The base address is per connection, so the client is not bound to a host. The
 * Authorization header is the value read from the environment, used only here and never logged; a failure becomes an
 * {@link ExternalTicketingException} that says which operation and which status code, and <b>never carries the response body</b>, which
 * could echo the request (and so the ticket text or the credentials).
 */
@Singleton
public class ProviderHttp {

    private static final Logger log = LoggerFactory.getLogger(ProviderHttp.class);
    private static final Argument<Map<String, Object>> JSON_MAP = Argument.mapOf(String.class, Object.class);

    private final HttpClient client;

    public ProviderHttp(@Client("/") HttpClient client) {
        this.client = client;
    }

    /** Sends the request and returns the JSON object of the answer (empty when there is no body). */
    public Mono<Map<String, Object>> call(String provider, String operation, HttpMethod method, String url, String authorization, Object body) {
        MutableHttpRequest<Object> request = HttpRequest.create(method, url)
                .header("Authorization", authorization)
                .header("Accept", "application/json");
        if (body != null) {
            request = request.body(body).contentType("application/json");
        }
        return Mono.from(client.exchange(request, JSON_MAP))
                .map(response -> response.getBody().orElse(Map.of()))
                .onErrorMap(error -> !(error instanceof ExternalTicketingException), error -> {
                    if (error instanceof HttpClientResponseException http) {
                        log.warn("[INTEGRATION] {} refused {}: HTTP {}", provider, operation, http.getStatus().getCode());
                        return new ExternalTicketingException(provider + " refused " + operation + " (HTTP " + http.getStatus().getCode() + ").");
                    }
                    log.warn("[INTEGRATION] {} could not be reached for {}: {}", provider, operation, error.getClass().getSimpleName());
                    return new ExternalTicketingException(provider + " could not be reached for " + operation + ".");
                });
    }
}
