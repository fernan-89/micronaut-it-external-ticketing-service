package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.InitiateTicketLinkRequest;
import com.thinklab.application.mapper.TicketLinkMapper;
import com.thinklab.domain.exception.ConnectionNotFoundException;
import com.thinklab.domain.exception.DuplicateTicketLinkException;
import com.thinklab.domain.exception.ExternalTicketingException;
import com.thinklab.domain.exception.IntegrationAccessDeniedException;
import com.thinklab.domain.exception.InvalidConnectionStatusException;
import com.thinklab.domain.exception.InvalidTicketLinkStatusException;
import com.thinklab.domain.exception.PlatformItemNotFoundException;
import com.thinklab.domain.exception.TicketLinkNotFoundException;
import com.thinklab.domain.model.Connection;
import com.thinklab.domain.model.Connection.Provider;
import com.thinklab.domain.model.TicketLink;
import com.thinklab.domain.model.TicketLink.LinkStatus;
import com.thinklab.domain.model.TicketLink.SubjectType;
import com.thinklab.domain.port.ExternalTicketingPort;
import com.thinklab.domain.port.ExternalTicketingPort.ExternalTicket;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.port.PlatformItemsPort;
import com.thinklab.domain.port.PlatformItemsPort.PlatformComment;
import com.thinklab.domain.port.PlatformItemsPort.PlatformItem;
import com.thinklab.domain.port.SecretResolverPort;
import com.thinklab.domain.repository.ConnectionRepository;
import com.thinklab.domain.repository.TicketLinkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketLinkUseCaseTest {

    @Mock private ConnectionRepository connectionRepository;
    @Mock private TicketLinkRepository linkRepository;
    @Mock private PlatformItemsPort platform;
    @Mock private ExternalTicketingPort jira;
    @Mock private SecretResolverPort secrets;
    @Mock private HashServicePort hashService;

    private final UUID org = UUID.randomUUID();
    private final UUID subject = UUID.randomUUID();
    private Connection connection;
    private TicketSyncService sync;

    @BeforeEach
    void setUp() {
        lenient().when(jira.provider()).thenReturn(Provider.JIRA);
        lenient().when(linkRepository.save(any(), any(), any())).thenReturn(Mono.empty());
        connection = Connection.createNew(UUID.randomUUID(), org, "Jira", Provider.JIRA, "https://acme.atlassian.net", "JIRA_AUTH", "JIRA_HOOK", "svc", "ITSM", null, null, "op");
        sync = new TicketSyncService(platform, new ProviderRegistry(List.of(jira)), secrets, linkRepository);
    }

    private TicketLink pending() {
        return TicketLink.createNew(UUID.randomUUID(), org, connection.getId(), SubjectType.INCIDENT, subject, "op");
    }

    private TicketLink linked() {
        TicketLink link = pending();
        link.markCreated("ITSM-1", "u", "NEW", "op");
        return link;
    }

    private PlatformItem item(String status, PlatformComment... comments) {
        return new PlatformItem(SubjectType.INCIDENT, subject, "Printer down", "It is on fire", status, List.of(comments));
    }

    private void itemIs(PlatformItem item) {
        when(platform.retrieve(eq(SubjectType.INCIDENT), eq(subject), eq(org), eq("external-ticketing:" + connection.getId()))).thenReturn(Mono.just(item));
    }

    private void secretIs(String value) {
        when(secrets.resolve("JIRA_AUTH")).thenReturn(value);
    }

    // ------------------------------------------------------------------ TicketSyncService: create

    @Test
    @DisplayName("sync of a link without a ticket creates it with the mapped status, saves it LINKED, and pushes the public comments only")
    void createsTheTicketAndPushesComments() {
        secretIs("Basic abc");
        PlatformComment publicOne = new PlatformComment(UUID.randomUUID(), "agent-1", "We are on it", false);
        PlatformComment internal = new PlatformComment(UUID.randomUUID(), "agent-1", "Secret", true);
        PlatformComment ours = new PlatformComment(UUID.randomUUID(), "external-ticketing:x", "[Jira] echo", false);
        itemIs(item("NEW", publicOne, internal, ours));
        when(jira.createTicket(any(), eq("Basic abc"), any())).thenReturn(Mono.just(new ExternalTicket("ITSM-7", "https://acme/browse/ITSM-7")));
        when(jira.addComment(any(), any(), eq("ITSM-7"), anyString())).thenReturn(Mono.empty());
        TicketLink link = pending();

        StepVerifier.create(sync.sync(link, connection, "op")).expectNext(link).verifyComplete();

        assertEquals(LinkStatus.LINKED, link.getStatus());
        assertEquals("ITSM-7", link.getExternalId());
        assertEquals("NEW", link.getLastPushedStatus());
        assertEquals(1, link.getSyncedCommentIds().size());
        verify(jira, times(1)).addComment(any(), any(), any(), anyString());
        verify(jira, never()).updateStatus(any(), any(), any(), any());
        ArgumentCaptor<ExternalTicketingPort.TicketDraft> draft = ArgumentCaptor.forClass(ExternalTicketingPort.TicketDraft.class);
        verify(jira).createTicket(any(), any(), draft.capture());
        assertEquals("To Do", draft.getValue().externalStatus());
        assertEquals(subject, draft.getValue().platformRef());
    }

    @Test
    @DisplayName("a status the connection does not map is created without one and is never pushed")
    void unmappedStatus() {
        secretIs("Basic abc");
        itemIs(item("WEIRD"));
        when(jira.createTicket(any(), any(), any())).thenReturn(Mono.just(new ExternalTicket("ITSM-8", null)));
        TicketLink link = pending();

        StepVerifier.create(sync.sync(link, connection, "op")).expectNext(link).verifyComplete();

        assertNull(link.getLastPushedStatus());
        verify(jira, never()).updateStatus(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a provider failure at creation leaves the link FAILED (retryable) and tells the caller")
    void createFailure() {
        secretIs("Basic abc");
        itemIs(item("NEW"));
        when(jira.createTicket(any(), any(), any())).thenReturn(Mono.error(new ExternalTicketingException("Jira refused creating the issue (HTTP 500).")));
        TicketLink link = pending();

        StepVerifier.create(sync.sync(link, connection, "op")).expectError(ExternalTicketingException.class).verify();

        assertEquals(LinkStatus.FAILED, link.getStatus());
        assertTrue(link.getLastError().contains("500"));
        verify(linkRepository).save(eq(link), eq(LinkStatus.PENDING), any());
    }

    @Test
    @DisplayName("a failed link is retried by the next sync")
    void retryAfterFailure() {
        secretIs("Basic abc");
        itemIs(item("NEW"));
        TicketLink link = pending();
        link.markCreateFailed("x", "op");
        when(jira.createTicket(any(), any(), any())).thenReturn(Mono.just(new ExternalTicket("ITSM-9", "u")));

        StepVerifier.create(sync.sync(link, connection, "op")).expectNext(link).verifyComplete();

        assertEquals(LinkStatus.LINKED, link.getStatus());
        verify(linkRepository).save(eq(link), eq(LinkStatus.FAILED), any());
    }

    @Test
    @DisplayName("a connection that is disabled, or whose credentials variable is unset, is not used")
    void notUsable() {
        TicketLink link = pending();
        secretIs(null);
        StepVerifier.create(sync.sync(link, connection, "op")).expectError(InvalidConnectionStatusException.class).verify();

        connection.disable("op");
        StepVerifier.create(sync.sync(link, connection, "op")).expectError(InvalidConnectionStatusException.class).verify();
        verify(platform, never()).retrieve(any(), any(), any(), any());
    }

    // ------------------------------------------------------------------ TicketSyncService: push

    @Test
    @DisplayName("a linked item whose status moved gets the status pushed, once; the comments already pushed are not pushed again")
    void pushesStatusAndNewComments() {
        secretIs("Basic abc");
        PlatformComment old = new PlatformComment(UUID.randomUUID(), "agent", "old", false);
        PlatformComment fresh = new PlatformComment(UUID.randomUUID(), "agent", "fresh", false);
        TicketLink link = linked();
        link.recordPush("NEW", java.util.Set.of(old.id()), false, "op");
        itemIs(item("RESOLVED", old, fresh));
        when(jira.updateStatus(any(), eq("Basic abc"), eq("ITSM-1"), eq("Done"))).thenReturn(Mono.empty());
        when(jira.addComment(any(), any(), eq("ITSM-1"), eq("fresh"))).thenReturn(Mono.empty());

        StepVerifier.create(sync.sync(link, connection, "op")).expectNext(link).verifyComplete();

        assertEquals("RESOLVED", link.getLastPushedStatus());
        assertTrue(link.getSyncedCommentIds().contains(fresh.id()));
        verify(jira, times(1)).addComment(any(), any(), any(), anyString());
        verify(linkRepository, times(2)).save(eq(link), eq(LinkStatus.LINKED), any());
    }

    @Test
    @DisplayName("a link that is up to date pushes nothing and records nothing")
    void upToDate() {
        secretIs("Basic abc");
        TicketLink link = linked();
        itemIs(item("NEW"));

        StepVerifier.create(sync.sync(link, connection, "op")).expectNext(link).verifyComplete();

        verify(jira, never()).updateStatus(any(), any(), any(), any());
        verify(linkRepository, never()).save(any(), any(), any());
    }

    @Test
    @DisplayName("at most 50 comments go in one sync; the rest wait for the next")
    void commentCap() {
        secretIs("Basic abc");
        TicketLink link = linked();
        List<PlatformComment> many = new ArrayList<>();
        for (int i = 0; i < TicketSyncService.MAX_COMMENTS_PER_SYNC + 5; i++) {
            many.add(new PlatformComment(UUID.randomUUID(), "agent", "c" + i, false));
        }
        itemIs(item("NEW", many.toArray(new PlatformComment[0])));
        when(jira.addComment(any(), any(), any(), anyString())).thenReturn(Mono.empty());

        StepVerifier.create(sync.sync(link, connection, "op")).expectNext(link).verifyComplete();

        verify(jira, times(TicketSyncService.MAX_COMMENTS_PER_SYNC)).addComment(any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("a push the provider refuses is recorded on the link (LINKED, sanitised) and surfaced")
    void pushFailure() {
        secretIs("Basic abc");
        TicketLink link = linked();
        itemIs(item("RESOLVED"));
        when(jira.updateStatus(any(), any(), any(), any())).thenReturn(Mono.error(new ExternalTicketingException("Jira has no transition to 'Done'.")));

        StepVerifier.create(sync.sync(link, connection, "op")).expectError(ExternalTicketingException.class).verify();

        assertEquals(LinkStatus.LINKED, link.getStatus());
        assertTrue(link.getLastError().contains("no transition"));
        assertEquals("NEW", link.getLastPushedStatus());
    }

    // ------------------------------------------------------------------ Initiate / Sync / Detach

    @Test
    @DisplayName("initiate links an item: the item must exist, the link is saved PENDING first, then the ticket is created")
    void initiate() {
        connectionRepositoryHas(connection);
        itemIs(item("NEW"));
        when(hashService.generateSovereignId("ticket-link-creation")).thenReturn(Mono.just(UUID.randomUUID()));
        when(linkRepository.create(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));
        secretIs("Basic abc");
        when(jira.createTicket(any(), any(), any())).thenReturn(Mono.just(new ExternalTicket("ITSM-1", "u")));

        StepVerifier.create(new InitiateTicketLinkUseCase(hashService, connectionRepository, linkRepository, platform, sync)
                        .execute(org, new InitiateTicketLinkRequest(connection.getId(), SubjectType.INCIDENT, subject), "op", "AGENT"))
                .assertNext(response -> {
                    assertEquals("LINKED", response.status());
                    assertEquals("ITSM-1", response.externalId());
                    assertEquals("INCIDENT", response.subjectType());
                }).verifyComplete();
    }

    @Test
    @DisplayName("initiate refuses a requester, an unknown or disabled connection, a missing item and a duplicate link")
    void initiateRefusals() {
        InitiateTicketLinkUseCase useCase = new InitiateTicketLinkUseCase(hashService, connectionRepository, linkRepository, platform, sync);
        InitiateTicketLinkRequest request = new InitiateTicketLinkRequest(connection.getId(), SubjectType.INCIDENT, subject);

        StepVerifier.create(useCase.execute(org, request, "op", "REQUESTER")).expectError(IntegrationAccessDeniedException.class).verify();

        when(connectionRepository.findById(connection.getId(), org)).thenReturn(Mono.empty());
        StepVerifier.create(useCase.execute(org, request, "op", null)).expectError(ConnectionNotFoundException.class).verify();

        Connection disabled = Connection.createNew(connection.getId(), org, "Jira", Provider.JIRA, "https://acme.atlassian.net", "JIRA_AUTH", "JIRA_HOOK", "svc", "ITSM", null, null, "op");
        disabled.disable("op");
        when(connectionRepository.findById(connection.getId(), org)).thenReturn(Mono.just(disabled));
        StepVerifier.create(useCase.execute(org, request, "op", null)).expectError(InvalidConnectionStatusException.class).verify();

        connectionRepositoryHas(connection);
        when(platform.retrieve(any(), any(), any(), any())).thenReturn(Mono.error(new PlatformItemNotFoundException("INCIDENT not found.")));
        StepVerifier.create(useCase.execute(org, request, "op", null)).expectError(PlatformItemNotFoundException.class).verify();

        itemIs(item("NEW"));
        when(hashService.generateSovereignId(any())).thenReturn(Mono.just(UUID.randomUUID()));
        when(linkRepository.create(any())).thenReturn(Mono.error(new DuplicateTicketLinkException("already linked")));
        StepVerifier.create(useCase.execute(org, request, "op", null)).expectError(DuplicateTicketLinkException.class).verify();
    }

    private void connectionRepositoryHas(Connection found) {
        when(connectionRepository.findById(found.getId(), org)).thenReturn(Mono.just(found));
    }

    @Test
    @DisplayName("sync-now pushes through the same routine; a detached link, an unknown link or connection and a requester are refused")
    void syncNow() {
        TicketLink link = linked();
        SyncTicketLinkUseCase useCase = new SyncTicketLinkUseCase(linkRepository, connectionRepository, sync);
        when(linkRepository.findById(link.getId(), org)).thenReturn(Mono.just(link));
        connectionRepositoryHas(connection);
        secretIs("Basic abc");
        itemIs(item("NEW"));

        StepVerifier.create(useCase.execute(link.getId(), org, "op", "AGENT")).assertNext(response -> assertEquals("ITSM-1", response.externalId())).verifyComplete();

        StepVerifier.create(useCase.execute(link.getId(), org, "op", "REQUESTER")).expectError(IntegrationAccessDeniedException.class).verify();
        UUID missing = UUID.randomUUID();
        when(linkRepository.findById(missing, org)).thenReturn(Mono.empty());
        StepVerifier.create(useCase.execute(missing, org, "op", null)).expectError(TicketLinkNotFoundException.class).verify();

        TicketLink detached = linked();
        detached.detach("op");
        when(linkRepository.findById(detached.getId(), org)).thenReturn(Mono.just(detached));
        StepVerifier.create(useCase.execute(detached.getId(), org, "op", null)).expectError(InvalidTicketLinkStatusException.class).verify();

        TicketLink orphan = TicketLink.createNew(UUID.randomUUID(), org, UUID.randomUUID(), SubjectType.INCIDENT, subject, "op");
        when(linkRepository.findById(orphan.getId(), org)).thenReturn(Mono.just(orphan));
        when(connectionRepository.findById(orphan.getConnectionId(), org)).thenReturn(Mono.empty());
        StepVerifier.create(useCase.execute(orphan.getId(), org, "op", null)).expectError(ConnectionNotFoundException.class).verify();
    }

    @Test
    @DisplayName("detach saves the link DETACHED with the status it had; refuses a requester, an unknown link and a second detach")
    void detach() {
        TicketLink link = linked();
        DetachTicketLinkUseCase useCase = new DetachTicketLinkUseCase(linkRepository);
        when(linkRepository.findById(link.getId(), org)).thenReturn(Mono.just(link));

        StepVerifier.create(useCase.execute(link.getId(), org, "op", "AGENT")).verifyComplete();
        verify(linkRepository).save(eq(link), eq(LinkStatus.LINKED), any());
        StepVerifier.create(useCase.execute(link.getId(), org, "op", null)).expectError(InvalidTicketLinkStatusException.class).verify();
        StepVerifier.create(useCase.execute(link.getId(), org, "op", "REQUESTER")).expectError(IntegrationAccessDeniedException.class).verify();
        UUID missing = UUID.randomUUID();
        when(linkRepository.findById(missing, org)).thenReturn(Mono.empty());
        StepVerifier.create(useCase.execute(missing, org, "op", null)).expectError(TicketLinkNotFoundException.class).verify();
    }

    // ------------------------------------------------------------------ Retrieve

    @Test
    @DisplayName("retrieve, list and audit log are for staff, scoped to the tenant, and 404 on an unknown link")
    void retrieves() {
        TicketLink link = linked();
        when(linkRepository.findById(link.getId(), org)).thenReturn(Mono.just(link));
        TicketLinkRepository.Filter filter = new TicketLinkRepository.Filter(null, null, subject, null);
        when(linkRepository.findAll(org, filter)).thenReturn(Flux.just(link));

        StepVerifier.create(new RetrieveTicketLinkUseCase(linkRepository).execute(link.getId(), org, "AGENT")).assertNext(r -> assertEquals(1, r.syncedComments() + 1)).verifyComplete();
        StepVerifier.create(new RetrieveTicketLinksUseCase(linkRepository).execute(org, filter, "AGENT")).expectNextCount(1).verifyComplete();
        StepVerifier.create(new RetrieveTicketLinkAuditLogUseCase(linkRepository).execute(link.getId(), org, "AGENT"))
                .assertNext(entries -> assertEquals(2, entries.size())).verifyComplete();

        StepVerifier.create(new RetrieveTicketLinkUseCase(linkRepository).execute(link.getId(), org, "REQUESTER")).expectError(IntegrationAccessDeniedException.class).verify();
        StepVerifier.create(new RetrieveTicketLinksUseCase(linkRepository).execute(org, filter, "REQUESTER")).expectError(IntegrationAccessDeniedException.class).verify();
        StepVerifier.create(new RetrieveTicketLinkAuditLogUseCase(linkRepository).execute(link.getId(), org, "REQUESTER")).expectError(IntegrationAccessDeniedException.class).verify();
        UUID missing = UUID.randomUUID();
        when(linkRepository.findById(missing, org)).thenReturn(Mono.empty());
        StepVerifier.create(new RetrieveTicketLinkUseCase(linkRepository).execute(missing, org, null)).expectError(TicketLinkNotFoundException.class).verify();
        StepVerifier.create(new RetrieveTicketLinkAuditLogUseCase(linkRepository).execute(missing, org, null)).expectError(TicketLinkNotFoundException.class).verify();
    }

    @Test
    @DisplayName("the mapper reports the direction when there is one, the audit status transitions, and is a utility class")
    void mapper() throws Exception {
        TicketLink link = pending();
        assertNull(TicketLinkMapper.toResponse(link).lastDirection());
        link.markCreated("E-1", "u", null, "op");
        assertEquals("OUTBOUND", TicketLinkMapper.toResponse(link).lastDirection());
        assertNull(TicketLinkMapper.toResponse(link.getAuditTrail().get(0)).fromStatus());
        assertEquals("PENDING", TicketLinkMapper.toResponse(link.getAuditTrail().get(1)).fromStatus());

        Constructor<TicketLinkMapper> constructor = TicketLinkMapper.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertThrows(InvocationTargetException.class, constructor::newInstance);
        assertFalse(link.getAuditTrail().isEmpty());
    }
}
