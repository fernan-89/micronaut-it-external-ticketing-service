package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.WebhookResultResponse;
import com.thinklab.domain.exception.ConnectionNotFoundException;
import com.thinklab.domain.exception.InvalidConnectionStatusException;
import com.thinklab.domain.exception.PlatformActionRefusedException;
import com.thinklab.domain.exception.WebhookUnauthorizedException;
import com.thinklab.domain.model.Connection;
import com.thinklab.domain.model.Connection.InboundAction;
import com.thinklab.domain.model.TicketLink;
import com.thinklab.domain.model.TicketLink.LinkStatus;
import com.thinklab.domain.port.ExternalTicketingPort.ExternalEvent;
import com.thinklab.domain.port.PlatformItemsPort;
import com.thinklab.domain.port.SecretResolverPort;
import com.thinklab.domain.repository.ConnectionRepository;
import com.thinklab.domain.repository.ProcessedEventRepository;
import com.thinklab.domain.repository.TicketLinkRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;

/**
 * Use Case for what the provider tells the platform (BIAN Behavior Qualifier: {@code webhook/receive}, ADR-031). No tenant header and no
 * role: the provider cannot send them, so the connection id in the path names the tenant and the shared token proves who is calling
 * (compared in constant time against the variable the connection names). Then, in order: the payload is read into one shape; an event seen
 * before is answered {@code DUPLICATE}; one caused by the integration's own account is ignored (it is the echo of what the platform just
 * pushed); a ticket the platform does not know is ignored; then the status is applied as the action the connection maps it to and a comment
 * is added to the item as an INTERNAL note (ADR-032). <b>The platform wins</b>: an action the item refuses is a recorded conflict, never
 * forced, and the answer is still 200 so the provider does not retry something that was decided on purpose.
 */
@Singleton
public class ReceiveWebhookUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReceiveWebhookUseCase.class);
    static final int MAX_COMMENT_LENGTH = 4000;

    private final ConnectionRepository connectionRepository;
    private final TicketLinkRepository linkRepository;
    private final ProcessedEventRepository processedEvents;
    private final SecretResolverPort secrets;
    private final ProviderRegistry providers;
    private final PlatformItemsPort platform;

    public ReceiveWebhookUseCase(ConnectionRepository connectionRepository, TicketLinkRepository linkRepository, ProcessedEventRepository processedEvents,
                                 SecretResolverPort secrets, ProviderRegistry providers, PlatformItemsPort platform) {
        this.connectionRepository = connectionRepository;
        this.linkRepository = linkRepository;
        this.processedEvents = processedEvents;
        this.secrets = secrets;
        this.providers = providers;
        this.platform = platform;
    }

    public Mono<WebhookResultResponse> execute(UUID connectionId, String token, Map<String, Object> payload) {
        log.info("[USE CASE] Webhook received for Connection ID: {}", connectionId);

        return connectionRepository.findByIdAnyTenant(connectionId)
                .switchIfEmpty(Mono.error(new ConnectionNotFoundException("Connection " + connectionId + " not found.")))
                .flatMap(connection -> {
                    authenticate(connection, token);
                    connection.requireActive();
                    ExternalEvent event = providers.of(connection.getProvider()).parseWebhook(connection, payload);
                    return processedEvents.markProcessed(connection.getId(), event.eventId())
                            .flatMap(isNew -> !isNew ? Mono.just(new WebhookResultResponse(WebhookResultResponse.DUPLICATE, "This event was already processed."))
                                    : process(connection, event));
                });
    }

    private void authenticate(Connection connection, String token) {
        String expected = secrets.resolve(connection.getWebhookSecretRef());
        if (expected == null) {
            throw new InvalidConnectionStatusException("The variable " + connection.getWebhookSecretRef() + " that holds the webhook token is not set in this environment.");
        }
        if (token == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            throw new WebhookUnauthorizedException("The webhook token is missing or wrong.");
        }
    }

    private Mono<WebhookResultResponse> process(Connection connection, ExternalEvent event) {
        if (connection.getIntegrationActor().equals(event.actor())) {
            return Mono.just(new WebhookResultResponse(WebhookResultResponse.IGNORED_OWN_EVENT, "The event was caused by the integration itself."));
        }
        return linkRepository.findByExternalId(connection.getId(), event.externalId(), connection.getOrganisationId())
                .filter(link -> link.getStatus() == LinkStatus.LINKED)
                .flatMap(link -> apply(connection, link, event))
                .switchIfEmpty(Mono.just(new WebhookResultResponse(WebhookResultResponse.IGNORED_UNKNOWN_TICKET, "No linked item follows that ticket.")));
    }

    private Mono<WebhookResultResponse> apply(Connection connection, TicketLink link, ExternalEvent event) {
        String executor = IntegrationAccess.EXECUTOR_PREFIX + connection.getId();
        InboundAction action = event.externalStatus() == null ? null : connection.getInboundActions().get(event.externalStatus());
        boolean hasComment = event.commentText() != null && !event.commentText().isBlank();
        if (action == null && !hasComment) {
            return Mono.just(new WebhookResultResponse(WebhookResultResponse.NOTHING_TO_APPLY, "The event holds no mapped status and no comment."));
        }
        return applyStatus(connection, link, action, event, executor)
                .flatMap(statusResult -> applyComment(connection, link, event, hasComment, executor)
                        .map(commentApplied -> new Applied(statusResult, commentApplied)))
                .flatMap(applied -> record(link, applied, executor));
    }

    /** Result of the status part: what happened, and the new platform status when it moved. */
    private record StatusResult(boolean applied, boolean conflict, String detail, String newPlatformStatus) {
    }

    private record Applied(StatusResult status, boolean commentApplied) {
    }

    private Mono<StatusResult> applyStatus(Connection connection, TicketLink link, InboundAction action, ExternalEvent event, String executor) {
        if (action == null) {
            return Mono.just(new StatusResult(false, false, null, null));
        }
        if (!action.supportedBy(link.getSubjectType())) {
            return Mono.just(new StatusResult(false, true, "A " + link.getSubjectType() + " cannot take the action " + action + ".", null));
        }
        String note = "Resolved in " + providerName(connection) + " (" + event.externalId() + ")";
        return platform.applyAction(link.getSubjectType(), link.getSubjectId(), link.getOrganisationId(), action, note, executor)
                .then(Mono.defer(() -> platform.retrieve(link.getSubjectType(), link.getSubjectId(), link.getOrganisationId(), executor)
                        .map(item -> new StatusResult(true, false, "Applied " + action + ".", item.status()))))
                .onErrorResume(PlatformActionRefusedException.class, refused -> Mono.just(new StatusResult(false, true, refused.getMessage(), null)));
    }

    private Mono<Boolean> applyComment(Connection connection, TicketLink link, ExternalEvent event, boolean hasComment, String executor) {
        if (!hasComment) {
            return Mono.just(false);
        }
        String text = "[" + providerName(connection) + " " + event.externalId() + "] " + event.commentText().trim();
        String bounded = text.length() > MAX_COMMENT_LENGTH ? text.substring(0, MAX_COMMENT_LENGTH) : text;
        return platform.addComment(link.getSubjectType(), link.getSubjectId(), link.getOrganisationId(), bounded, executor).thenReturn(true);
    }

    private Mono<WebhookResultResponse> record(TicketLink link, Applied applied, String executor) {
        StatusResult status = applied.status();
        Mono<Void> inbound = status.applied() || applied.commentApplied()
                ? save(link, link.recordInbound(describe(status, applied.commentApplied()), status.newPlatformStatus(), executor))
                : Mono.empty();
        Mono<Void> conflict = status.conflict() ? save(link, link.recordInboundConflict(status.detail(), executor)) : Mono.empty();
        WebhookResultResponse result = status.conflict()
                ? new WebhookResultResponse(WebhookResultResponse.CONFLICT_PLATFORM_WINS, status.detail())
                : new WebhookResultResponse(WebhookResultResponse.APPLIED, describe(status, applied.commentApplied()));
        return inbound.then(conflict).thenReturn(result);
    }

    private Mono<Void> save(TicketLink link, TicketLink.LinkAuditEntry entry) {
        return linkRepository.save(link, LinkStatus.LINKED, entry);
    }

    private static String describe(StatusResult status, boolean commentApplied) {
        return ((status.applied() ? status.detail() + " " : "") + (commentApplied ? "Comment added as an internal note." : "")).trim();
    }

    private static String providerName(Connection connection) {
        return connection.getProvider() == Connection.Provider.JIRA ? "Jira" : "ServiceNow";
    }
}
