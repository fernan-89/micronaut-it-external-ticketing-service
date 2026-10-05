package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.WebhookResultResponse;
import com.thinklab.domain.exception.ConnectionNotFoundException;
import com.thinklab.domain.exception.InvalidConnectionStatusException;
import com.thinklab.domain.exception.PlatformActionRefusedException;
import com.thinklab.domain.exception.WebhookUnauthorizedException;
import com.thinklab.domain.model.Connection;
import com.thinklab.domain.model.Connection.InboundAction;
import com.thinklab.domain.model.Connection.Provider;
import com.thinklab.domain.model.TicketLink;
import com.thinklab.domain.model.TicketLink.LinkStatus;
import com.thinklab.domain.model.TicketLink.SubjectType;
import com.thinklab.domain.port.ExternalTicketingPort;
import com.thinklab.domain.port.ExternalTicketingPort.ExternalEvent;
import com.thinklab.domain.port.PlatformItemsPort;
import com.thinklab.domain.port.PlatformItemsPort.PlatformItem;
import com.thinklab.domain.port.SecretResolverPort;
import com.thinklab.domain.repository.ConnectionRepository;
import com.thinklab.domain.repository.ProcessedEventRepository;
import com.thinklab.domain.repository.TicketLinkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReceiveWebhookUseCaseTest {

    private static final String TOKEN = "s3cr3t-token";

    @Mock private ConnectionRepository connectionRepository;
    @Mock private TicketLinkRepository linkRepository;
    @Mock private ProcessedEventRepository processedEvents;
    @Mock private SecretResolverPort secrets;
    @Mock private ExternalTicketingPort jira;
    @Mock private PlatformItemsPort platform;

    private final UUID org = UUID.randomUUID();
    private final UUID subject = UUID.randomUUID();
    private final Map<String, Object> payload = Map.of("any", "thing");
    private Connection connection;
    private TicketLink link;
    private ReceiveWebhookUseCase useCase;

    @BeforeEach
    void setUp() {
        lenient().when(jira.provider()).thenReturn(Provider.JIRA);
        lenient().when(linkRepository.save(any(), any(), any())).thenReturn(Mono.empty());
        connection = Connection.createNew(UUID.randomUUID(), org, "Jira", Provider.JIRA, "https://acme.atlassian.net", "JIRA_AUTH", "JIRA_HOOK", "svc-bot", "ITSM", null,
                Map.of("Done", InboundAction.RESOLVE, "Odd", InboundAction.ACKNOWLEDGE, "Going", InboundAction.START), "op");
        link = TicketLink.createNew(UUID.randomUUID(), org, connection.getId(), SubjectType.INCIDENT, subject, "op");
        link.markCreated("ITSM-1", "u", "NEW", "op");
        lenient().when(connectionRepository.findByIdAnyTenant(connection.getId())).thenReturn(Mono.just(connection));
        lenient().when(secrets.resolve("JIRA_HOOK")).thenReturn(TOKEN);
        lenient().when(processedEvents.markProcessed(any(), any())).thenReturn(Mono.just(true));
        lenient().when(linkRepository.findByExternalId(connection.getId(), "ITSM-1", org)).thenReturn(Mono.just(link));
        useCase = new ReceiveWebhookUseCase(connectionRepository, linkRepository, processedEvents, secrets, new ProviderRegistry(List.of(jira)), platform);
    }

    private void eventIs(String status, String comment, String actor) {
        when(jira.parseWebhook(connection, payload)).thenReturn(new ExternalEvent("evt-1", "ITSM-1", status, comment == null ? null : "c-1", comment, actor));
    }

    private void platformStatusAfter(String status) {
        when(platform.retrieve(eq(SubjectType.INCIDENT), eq(subject), eq(org), anyString()))
                .thenReturn(Mono.just(new PlatformItem(SubjectType.INCIDENT, subject, "t", "d", status, List.of())));
    }

    private WebhookResultResponse run() {
        return useCase.execute(connection.getId(), TOKEN, payload).block();
    }

    @Test
    @DisplayName("an unknown connection is a 404; a missing, wrong or unset token is refused before the payload is read")
    void authentication() {
        UUID unknown = UUID.randomUUID();
        when(connectionRepository.findByIdAnyTenant(unknown)).thenReturn(Mono.empty());
        StepVerifier.create(useCase.execute(unknown, TOKEN, payload)).expectError(ConnectionNotFoundException.class).verify();

        StepVerifier.create(useCase.execute(connection.getId(), null, payload)).expectError(WebhookUnauthorizedException.class).verify();
        StepVerifier.create(useCase.execute(connection.getId(), "wrong", payload)).expectError(WebhookUnauthorizedException.class).verify();
        when(secrets.resolve("JIRA_HOOK")).thenReturn(null);
        StepVerifier.create(useCase.execute(connection.getId(), TOKEN, payload)).expectError(InvalidConnectionStatusException.class).verify();
        verify(jira, never()).parseWebhook(any(), any());
    }

    @Test
    @DisplayName("a disabled connection accepts nothing")
    void disabled() {
        connection.disable("op");

        StepVerifier.create(useCase.execute(connection.getId(), TOKEN, payload)).expectError(InvalidConnectionStatusException.class).verify();
    }

    @Test
    @DisplayName("an event seen before is answered DUPLICATE and applies nothing")
    void duplicate() {
        eventIs("Done", null, "alice");
        when(processedEvents.markProcessed(connection.getId(), "evt-1")).thenReturn(Mono.just(false));

        assertEquals(WebhookResultResponse.DUPLICATE, run().outcome());
        verify(platform, never()).applyAction(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("an event caused by the integration's own account is ignored: it is the echo of what the platform pushed")
    void ownEvent() {
        eventIs("Done", null, "svc-bot");

        assertEquals(WebhookResultResponse.IGNORED_OWN_EVENT, run().outcome());
        verify(platform, never()).applyAction(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a ticket no linked item follows (unknown, or its link was detached) is ignored")
    void unknownTicket() {
        when(linkRepository.findByExternalId(connection.getId(), "ITSM-9", org)).thenReturn(Mono.empty());
        when(jira.parseWebhook(connection, payload)).thenReturn(new ExternalEvent("evt-1", "ITSM-9", "Done", null, null, "alice"));
        assertEquals(WebhookResultResponse.IGNORED_UNKNOWN_TICKET, run().outcome());

        link.detach("op");
        eventIs("Done", null, "alice");
        assertEquals(WebhookResultResponse.IGNORED_UNKNOWN_TICKET, run().outcome());
    }

    @Test
    @DisplayName("an event with no mapped status and no comment has nothing to apply")
    void nothingToApply() {
        eventIs("Unmapped", null, "alice");
        assertEquals(WebhookResultResponse.NOTHING_TO_APPLY, run().outcome());

        when(jira.parseWebhook(connection, payload)).thenReturn(new ExternalEvent("evt-1", "ITSM-1", null, "c", "   ", "alice"));
        assertEquals(WebhookResultResponse.NOTHING_TO_APPLY, run().outcome());
        verify(platform, never()).applyAction(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a mapped status is applied to the item as the mapped action, by the integration's identity, and is not echoed back")
    void appliesStatus() {
        eventIs("Done", null, "alice");
        when(platform.applyAction(eq(SubjectType.INCIDENT), eq(subject), eq(org), eq(InboundAction.RESOLVE), eq("Resolved in Jira (ITSM-1)"), eq("external-ticketing:" + connection.getId())))
                .thenReturn(Mono.empty());
        platformStatusAfter("RESOLVED");

        WebhookResultResponse result = run();

        assertEquals(WebhookResultResponse.APPLIED, result.outcome());
        assertEquals("Applied RESOLVE.", result.detail());
        assertEquals("RESOLVED", link.getLastPushedStatus());
        assertEquals(TicketLink.Direction.INBOUND, link.getLastDirection());
        verify(linkRepository).save(eq(link), eq(LinkStatus.LINKED), any());
    }

    @Test
    @DisplayName("a comment alone is added as an INTERNAL note prefixed with its origin, and a long one is cut")
    void commentOnly() {
        eventIs("Unmapped", "  please check  ", "alice");
        when(platform.addComment(eq(SubjectType.INCIDENT), eq(subject), eq(org), eq("[Jira ITSM-1] please check"), anyString())).thenReturn(Mono.empty());

        WebhookResultResponse result = run();

        assertEquals(WebhookResultResponse.APPLIED, result.outcome());
        assertEquals("Comment added as an internal note.", result.detail());
        assertEquals("NEW", link.getLastPushedStatus());

        when(jira.parseWebhook(connection, payload)).thenReturn(new ExternalEvent("evt-2", "ITSM-1", null, "c", "x".repeat(5000), "alice"));
        when(platform.addComment(any(), any(), any(), argThat(text -> text != null && text.length() == ReceiveWebhookUseCase.MAX_COMMENT_LENGTH), any()))
                .thenReturn(Mono.empty());
        assertEquals(WebhookResultResponse.APPLIED, run().outcome());
    }

    @Test
    @DisplayName("a status and a comment in one event are both applied and recorded as one entry")
    void statusAndComment() {
        eventIs("Done", "all fixed", "alice");
        when(platform.applyAction(any(), any(), any(), eq(InboundAction.RESOLVE), any(), any())).thenReturn(Mono.empty());
        platformStatusAfter("RESOLVED");
        when(platform.addComment(any(), any(), any(), anyString(), any())).thenReturn(Mono.empty());

        WebhookResultResponse result = run();

        assertEquals(WebhookResultResponse.APPLIED, result.outcome());
        assertTrue(result.detail().contains("Applied RESOLVE.") && result.detail().contains("Comment added"));
    }

    @Test
    @DisplayName("the platform wins: an action the item cannot take, or refuses, is a recorded conflict and the answer is still a normal result")
    void platformWins() {
        eventIs("Done", null, "alice");
        when(platform.applyAction(any(), any(), any(), any(), any(), any())).thenReturn(Mono.error(new PlatformActionRefusedException("Illegal transition: Incident is CLOSED")));

        WebhookResultResponse refused = run();

        assertEquals(WebhookResultResponse.CONFLICT_PLATFORM_WINS, refused.outcome());
        assertTrue(refused.detail().contains("CLOSED"));
        assertTrue(link.getLastError().contains("CLOSED"));
        assertEquals("NEW", link.getLastPushedStatus());
    }

    @Test
    @DisplayName("an action the kind of item does not model is a conflict, without calling the platform")
    void unsupportedAction() {
        TicketLink problem = TicketLink.createNew(UUID.randomUUID(), org, connection.getId(), SubjectType.PROBLEM, subject, "op");
        problem.markCreated("ITSM-1", "u", null, "op");
        when(linkRepository.findByExternalId(connection.getId(), "ITSM-1", org)).thenReturn(Mono.just(problem));
        eventIs("Odd", null, "alice");

        WebhookResultResponse result = run();

        assertEquals(WebhookResultResponse.CONFLICT_PLATFORM_WINS, result.outcome());
        assertTrue(result.detail().contains("PROBLEM"));
        verify(platform, never()).applyAction(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a refused status with a comment still imports the comment, and records both the import and the conflict")
    void conflictWithComment() {
        eventIs("Done", "context", "alice");
        when(platform.applyAction(any(), any(), any(), any(), any(), any())).thenReturn(Mono.error(new PlatformActionRefusedException("nope")));
        when(platform.addComment(any(), any(), any(), anyString(), any())).thenReturn(Mono.empty());

        WebhookResultResponse result = run();

        assertEquals(WebhookResultResponse.CONFLICT_PLATFORM_WINS, result.outcome());
        verify(linkRepository, times(2)).save(eq(link), eq(LinkStatus.LINKED), any());
    }

    @Test
    @DisplayName("the comment of a ServiceNow event names ServiceNow as its origin")
    void serviceNowOrigin() {
        ExternalTicketingPort snow = mock(ExternalTicketingPort.class);
        when(snow.provider()).thenReturn(Provider.SERVICENOW);
        Connection snowConnection = Connection.createNew(UUID.randomUUID(), org, "SNOW", Provider.SERVICENOW, "https://acme.service-now.com", "SNOW_AUTH", "SNOW_HOOK", "svc-bot",
                null, null, null, "op");
        TicketLink snowLink = TicketLink.createNew(UUID.randomUUID(), org, snowConnection.getId(), SubjectType.INCIDENT, subject, "op");
        snowLink.markCreated("incident/abc", "u", null, "op");
        when(connectionRepository.findByIdAnyTenant(snowConnection.getId())).thenReturn(Mono.just(snowConnection));
        when(secrets.resolve("SNOW_HOOK")).thenReturn(TOKEN);
        when(linkRepository.findByExternalId(snowConnection.getId(), "incident/abc", org)).thenReturn(Mono.just(snowLink));
        when(snow.parseWebhook(snowConnection, payload)).thenReturn(new ExternalEvent("e-1", "incident/abc", null, null, "hello", "alice"));
        when(platform.addComment(eq(SubjectType.INCIDENT), eq(subject), eq(org), eq("[ServiceNow incident/abc] hello"), anyString())).thenReturn(Mono.empty());
        ReceiveWebhookUseCase snowUseCase = new ReceiveWebhookUseCase(connectionRepository, linkRepository, processedEvents, secrets, new ProviderRegistry(List.of(snow)), platform);

        assertEquals(WebhookResultResponse.APPLIED, snowUseCase.execute(snowConnection.getId(), TOKEN, payload).block().outcome());
    }
}
