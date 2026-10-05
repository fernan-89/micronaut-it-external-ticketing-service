package com.thinklab.application.usecase;

import com.thinklab.domain.exception.ExternalTicketingException;
import com.thinklab.domain.exception.InvalidConnectionStatusException;
import com.thinklab.domain.model.Connection;
import com.thinklab.domain.model.TicketLink;
import com.thinklab.domain.model.TicketLink.LinkStatus;
import com.thinklab.domain.port.ExternalTicketingPort;
import com.thinklab.domain.port.ExternalTicketingPort.TicketDraft;
import com.thinklab.domain.port.PlatformItemsPort;
import com.thinklab.domain.port.PlatformItemsPort.PlatformComment;
import com.thinklab.domain.port.PlatformItemsPort.PlatformItem;
import com.thinklab.domain.port.SecretResolverPort;
import com.thinklab.domain.repository.TicketLinkRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Pushes what the platform has to the provider (ADR-031), the one routine behind both "link this" and "sync now":
 * <ol>
 *   <li>a link with no ticket yet (PENDING, or FAILED at the first attempt) gets its ticket created, and the link is saved
 *       {@code LINKED} with the ticket id; a provider failure leaves it {@code FAILED}, to be retried by the next sync;</li>
 *   <li>a linked one gets its status pushed when it is not the one last pushed (and only if the connection maps it), and then its public
 *       comments, one at a time, each remembered as soon as the provider has it, so a failure half way never makes a comment go twice.</li>
 * </ol>
 * Internal comments, and comments the integration itself wrote, never leave the platform (ADR-032). Nothing is pushed for a link that is
 * already up to date, and nothing is recorded for it.
 */
@Singleton
public class TicketSyncService {

    private static final Logger log = LoggerFactory.getLogger(TicketSyncService.class);
    static final int MAX_COMMENTS_PER_SYNC = 50;

    private final PlatformItemsPort platform;
    private final ProviderRegistry providers;
    private final SecretResolverPort secrets;
    private final TicketLinkRepository linkRepository;

    public TicketSyncService(PlatformItemsPort platform, ProviderRegistry providers, SecretResolverPort secrets, TicketLinkRepository linkRepository) {
        this.platform = platform;
        this.providers = providers;
        this.secrets = secrets;
        this.linkRepository = linkRepository;
    }

    public Mono<TicketLink> sync(TicketLink link, Connection connection, String executor) {
        return Mono.fromCallable(() -> {
                    connection.requireActive();
                    String authorization = secrets.resolve(connection.getSecretRef());
                    if (authorization == null) {
                        throw new InvalidConnectionStatusException("The variable " + connection.getSecretRef() + " that holds the credentials is not set in this environment.");
                    }
                    return authorization;
                })
                .flatMap(authorization -> platform.retrieve(link.getSubjectType(), link.getSubjectId(), link.getOrganisationId(), IntegrationAccess.EXECUTOR_PREFIX + connection.getId())
                        .flatMap(item -> link.getExternalId() == null
                                ? create(link, connection, authorization, item, executor).flatMap(created -> push(created, connection, authorization, item, executor))
                                : push(link, connection, authorization, item, executor)));
    }

    private Mono<TicketLink> create(TicketLink link, Connection connection, String authorization, PlatformItem item, String executor) {
        LinkStatus before = link.getStatus();
        ExternalTicketingPort provider = providers.of(connection.getProvider());
        var draft = new TicketDraft(link.getSubjectType(), item.title(), item.description(), connection.getOutboundStatus().get(item.status()), link.getSubjectId());
        return provider.createTicket(connection, authorization, draft)
                .flatMap(ticket -> {
                    var entry = link.markCreated(ticket.externalId(), ticket.url(), draft.externalStatus() != null ? item.status() : null, executor);
                    return linkRepository.save(link, before, entry).thenReturn(link);
                })
                .onErrorResume(ExternalTicketingException.class, failure -> {
                    log.warn("[USE CASE] The provider could not create the ticket for link {}: {}", link.getId(), failure.getMessage());
                    var entry = link.markCreateFailed(failure.getMessage(), executor);
                    return linkRepository.save(link, before, entry).then(Mono.error(failure));
                });
    }

    private Mono<TicketLink> push(TicketLink link, Connection connection, String authorization, PlatformItem item, String executor) {
        ExternalTicketingPort provider = providers.of(connection.getProvider());
        String mapped = connection.getOutboundStatus().get(item.status());
        boolean statusDue = mapped != null && !item.status().equals(link.getLastPushedStatus());
        Mono<Void> status = statusDue
                ? provider.updateStatus(connection, authorization, link.getExternalId(), mapped)
                        .then(Mono.defer(() -> linkRepository.save(link, LinkStatus.LINKED, link.recordPush(item.status(), Set.of(), true, executor))))
                : Mono.empty();
        List<PlatformComment> due = item.comments().stream()
                .filter(comment -> !comment.internal() && !comment.author().startsWith(IntegrationAccess.EXECUTOR_PREFIX) && !link.getSyncedCommentIds().contains(comment.id()))
                .limit(MAX_COMMENTS_PER_SYNC)
                .toList();
        Mono<Void> comments = Flux.fromIterable(due)
                .concatMap(comment -> provider.addComment(connection, authorization, link.getExternalId(), comment.text())
                        .then(Mono.defer(() -> linkRepository.save(link, LinkStatus.LINKED, link.recordPush(item.status(), Set.of(comment.id()), false, executor)))))
                .then();
        return status.then(comments).thenReturn(link)
                .onErrorResume(ExternalTicketingException.class, failure -> {
                    log.warn("[USE CASE] The provider refused the push for link {}: {}", link.getId(), failure.getMessage());
                    var entry = link.recordPushFailure(failure.getMessage(), executor);
                    return linkRepository.save(link, LinkStatus.LINKED, entry).then(Mono.error(failure));
                });
    }
}
