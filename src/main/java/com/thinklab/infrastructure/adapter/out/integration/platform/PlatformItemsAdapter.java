package com.thinklab.infrastructure.adapter.out.integration.platform;

import com.thinklab.domain.exception.PlatformActionRefusedException;
import com.thinklab.domain.exception.PlatformItemNotFoundException;
import com.thinklab.domain.model.Connection.InboundAction;
import com.thinklab.domain.model.TicketLink.SubjectType;
import com.thinklab.domain.port.PlatformItemsPort;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Adapter to the platform's own incident, service request and problem services (ADR-031), through their public routes and as a service
 * identity ({@code X-Executor = external-ticketing:<connection>}), so each change it makes shows in their audit trails under that name.
 * Each service speaks its own vocabulary; this class is where {@link InboundAction} becomes the right control route. What a service does not
 * model is mapped to the safest reading: a service request has no title (its catalog item name stands in, and its answers, which can hold
 * personal data, never leave), and a problem comment has no public flag, so it is treated as an internal note and never pushed.
 */
@Singleton
public class PlatformItemsAdapter implements PlatformItemsPort {

    private static final Logger log = LoggerFactory.getLogger(PlatformItemsAdapter.class);

    private final IncidentApiClient incidents;
    private final ServiceRequestApiClient requests;
    private final ProblemApiClient problems;

    public PlatformItemsAdapter(IncidentApiClient incidents, ServiceRequestApiClient requests, ProblemApiClient problems) {
        this.incidents = incidents;
        this.requests = requests;
        this.problems = problems;
    }

    @Override
    public Mono<PlatformItem> retrieve(SubjectType type, UUID id, UUID organisationId, String executor) {
        String tenant = organisationId.toString();
        Mono<ItemApiResponse> response = switch (type) {
            case INCIDENT -> incidents.retrieve(id, tenant, executor);
            case SERVICE_REQUEST -> requests.retrieve(id, tenant, executor);
            case PROBLEM -> problems.retrieve(id, tenant, executor);
        };
        return response
                .map(item -> toItem(type, id, item))
                .onErrorMap(HttpClientResponseException.class, error -> relay(error, type + " " + id));
    }

    @Override
    public Mono<Void> applyAction(SubjectType type, UUID id, UUID organisationId, InboundAction action, String note, String executor) {
        String tenant = organisationId.toString();
        Mono<Void> call = switch (type) {
            case INCIDENT -> switch (action) {
                case ACKNOWLEDGE -> incidents.acknowledge(id, tenant, executor);
                case START -> incidents.start(id, tenant, executor);
                case RESUME -> incidents.resume(id, tenant, executor);
                case RESOLVE -> incidents.resolve(id, tenant, executor, new IncidentResolveApiRequest("RESOLVED_EXTERNALLY", note));
                case CLOSE -> incidents.close(id, tenant, executor);
                case CANCEL -> incidents.cancel(id, tenant, executor);
            };
            case SERVICE_REQUEST -> switch (action) {
                case START -> requests.startFulfilment(id, tenant, executor);
                case RESOLVE -> requests.fulfil(id, tenant, executor, new NotesApiRequest(note));
                case CLOSE -> requests.close(id, tenant, executor);
                case CANCEL -> requests.cancel(id, tenant, executor);
                default -> Mono.error(new PlatformActionRefusedException("A SERVICE_REQUEST cannot take the action " + action + "."));
            };
            case PROBLEM -> switch (action) {
                case START -> problems.investigate(id, tenant, executor);
                case RESOLVE -> problems.resolve(id, tenant, executor, new ProblemResolveApiRequest(note));
                case CLOSE -> problems.close(id, tenant, executor);
                case CANCEL -> problems.cancel(id, tenant, executor);
                default -> Mono.error(new PlatformActionRefusedException("A PROBLEM cannot take the action " + action + "."));
            };
        };
        return call.onErrorMap(HttpClientResponseException.class, error -> relay(error, type + " " + id));
    }

    @Override
    public Mono<Void> addComment(SubjectType type, UUID id, UUID organisationId, String text, String executor) {
        String tenant = organisationId.toString();
        Mono<Void> call = switch (type) {
            case INCIDENT -> incidents.comment(id, tenant, executor, new CommentApiRequest(text, true));
            case SERVICE_REQUEST -> requests.comment(id, tenant, executor, new CommentApiRequest(text, true));
            case PROBLEM -> problems.comment(id, tenant, executor, new ProblemCommentApiRequest(text));
        };
        return call.onErrorMap(HttpClientResponseException.class, error -> relay(error, type + " " + id));
    }

    static PlatformItem toItem(SubjectType type, UUID id, ItemApiResponse item) {
        String title = item.title() != null ? item.title() : item.catalogItemName();
        String description = item.description() != null ? item.description()
                : "Service request for " + item.catalogItemName() + (item.catalogItemCode() != null ? " (" + item.catalogItemCode() + ")" : "") + ".";
        List<CommentApiResponse> comments = item.comments() == null ? List.of() : item.comments();
        return new PlatformItem(type, id, title, description, item.status(),
                comments.stream().map(c -> new PlatformComment(c.commentId(), c.author(), c.text(), type == SubjectType.PROBLEM || c.internal())).toList());
    }

    /** A 404 is a missing item, any other 4xx the item refusing; a failure of the service itself stays a failure. */
    static RuntimeException relay(HttpClientResponseException error, String what) {
        HttpStatus status = error.getStatus();
        if (status == HttpStatus.NOT_FOUND) {
            return new PlatformItemNotFoundException(what + " not found.");
        }
        if (status.getCode() < 500) {
            String reason = error.getResponse().getBody(Map.class).map(body -> body.get("detail")).map(String::valueOf).orElse("refused (HTTP " + status.getCode() + ")");
            log.info("[INTEGRATION] The platform refused the action on {}: HTTP {}", what, status.getCode());
            return new PlatformActionRefusedException(reason);
        }
        return new IllegalStateException("Dependency Failure: the platform service for " + what + " is currently unavailable", error);
    }

    @Serdeable
    @Introspected
    public record ItemApiResponse(String title, String description, String status, String catalogItemName, String catalogItemCode, List<CommentApiResponse> comments) {}

    @Serdeable
    @Introspected
    public record CommentApiResponse(UUID commentId, String author, String text, boolean internal) {}

    @Serdeable
    @Introspected
    public record CommentApiRequest(String text, boolean internal) {}

    @Serdeable
    @Introspected
    public record ProblemCommentApiRequest(String text) {}

    @Serdeable
    @Introspected
    public record IncidentResolveApiRequest(String resolutionCode, String notes) {}

    @Serdeable
    @Introspected
    public record NotesApiRequest(String notes) {}

    @Serdeable
    @Introspected
    public record ProblemResolveApiRequest(String resolution) {}
}
