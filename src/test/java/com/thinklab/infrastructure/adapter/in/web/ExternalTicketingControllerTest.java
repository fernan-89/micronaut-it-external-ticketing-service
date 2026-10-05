package com.thinklab.infrastructure.adapter.in.web;

import com.thinklab.application.dto.request.InitiateConnectionRequest;
import com.thinklab.application.dto.request.InitiateTicketLinkRequest;
import com.thinklab.application.dto.request.UpdateConnectionRequest;
import com.thinklab.application.dto.response.AuditEntryResponse;
import com.thinklab.application.dto.response.ConnectionCheckResponse;
import com.thinklab.application.dto.response.ConnectionResponse;
import com.thinklab.application.dto.response.TicketLinkResponse;
import com.thinklab.application.dto.response.WebhookResultResponse;
import com.thinklab.application.usecase.CheckConnectionUseCase;
import com.thinklab.application.usecase.ControlConnectionUseCase;
import com.thinklab.application.usecase.DetachTicketLinkUseCase;
import com.thinklab.application.usecase.InitiateConnectionUseCase;
import com.thinklab.application.usecase.InitiateTicketLinkUseCase;
import com.thinklab.application.usecase.ReceiveWebhookUseCase;
import com.thinklab.application.usecase.RetrieveConnectionAuditLogUseCase;
import com.thinklab.application.usecase.RetrieveConnectionUseCase;
import com.thinklab.application.usecase.RetrieveConnectionsUseCase;
import com.thinklab.application.usecase.RetrieveTicketLinkAuditLogUseCase;
import com.thinklab.application.usecase.RetrieveTicketLinkUseCase;
import com.thinklab.application.usecase.RetrieveTicketLinksUseCase;
import com.thinklab.application.usecase.SyncTicketLinkUseCase;
import com.thinklab.application.usecase.UpdateConnectionUseCase;
import com.thinklab.domain.model.Connection.Provider;
import com.thinklab.domain.model.TicketLink.LinkStatus;
import com.thinklab.domain.model.TicketLink.SubjectType;
import com.thinklab.domain.repository.TicketLinkRepository;
import io.micronaut.http.HttpStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The controller only reads headers and delegates: tenant and role always travel to the use case; the webhook has neither. */
@ExtendWith(MockitoExtension.class)
class ExternalTicketingControllerTest {

    private static final String EXECUTOR = "op-1";
    private final UUID tenant = UUID.randomUUID();
    private final String tenantHeader = tenant.toString();
    private final UUID id = UUID.randomUUID();

    @Mock private InitiateConnectionUseCase initiateConnection;
    @Mock private RetrieveConnectionUseCase retrieveConnection;
    @Mock private RetrieveConnectionsUseCase retrieveConnections;
    @Mock private UpdateConnectionUseCase updateConnection;
    @Mock private ControlConnectionUseCase controlConnection;
    @Mock private CheckConnectionUseCase checkConnection;
    @Mock private RetrieveConnectionAuditLogUseCase connectionAuditLog;
    @Mock private InitiateTicketLinkUseCase initiateLink;
    @Mock private RetrieveTicketLinkUseCase retrieveLink;
    @Mock private RetrieveTicketLinksUseCase retrieveLinks;
    @Mock private SyncTicketLinkUseCase syncLink;
    @Mock private DetachTicketLinkUseCase detachLink;
    @Mock private RetrieveTicketLinkAuditLogUseCase linkAuditLog;
    @Mock private ReceiveWebhookUseCase receiveWebhook;

    private ExternalTicketingController controller;

    @BeforeEach
    void setUp() {
        controller = new ExternalTicketingController(initiateConnection, retrieveConnection, retrieveConnections, updateConnection, controlConnection, checkConnection,
                connectionAuditLog, initiateLink, retrieveLink, retrieveLinks, syncLink, detachLink, linkAuditLog, receiveWebhook);
    }

    private ConnectionResponse connectionResponse() {
        return new ConnectionResponse(id, tenant, "Jira", "JIRA", "https://x", "A_B", true, "C_D", true, "svc", "ITSM", Map.of(), Map.of(), "ACTIVE", Instant.now(), Instant.now());
    }

    private TicketLinkResponse linkResponse() {
        return new TicketLinkResponse(id, tenant, UUID.randomUUID(), "INCIDENT", UUID.randomUUID(), "ITSM-1", "u", "LINKED", "NEW", 0, null, null, null, Instant.now(), Instant.now());
    }

    @Test
    @DisplayName("connection routes delegate with the tenant, the executor and the role")
    void connectionRoutes() {
        var initiate = new InitiateConnectionRequest("Jira", Provider.JIRA, "https://x", "A_B", "C_D", "svc", "ITSM", null, null);
        var update = new UpdateConnectionRequest("Jira", "https://x", "A_B", "C_D", "svc", "ITSM", null, null);
        when(initiateConnection.execute(tenant, initiate, EXECUTOR, "AGENT")).thenReturn(Mono.just(connectionResponse()));
        when(retrieveConnection.execute(id, tenant, "AGENT")).thenReturn(Mono.just(connectionResponse()));
        when(retrieveConnections.execute(tenant, "AGENT")).thenReturn(Flux.just(connectionResponse()));
        when(updateConnection.execute(id, tenant, update, EXECUTOR, "AGENT")).thenReturn(Mono.empty());
        when(controlConnection.execute(id, tenant, ControlConnectionUseCase.Action.ENABLE, EXECUTOR, "AGENT")).thenReturn(Mono.empty());
        when(controlConnection.execute(id, tenant, ControlConnectionUseCase.Action.DISABLE, EXECUTOR, "AGENT")).thenReturn(Mono.empty());
        when(checkConnection.execute(id, tenant, "AGENT")).thenReturn(Mono.just(new ConnectionCheckResponse(true, true, true, null)));
        when(connectionAuditLog.execute(id, tenant, "AGENT")).thenReturn(Mono.just(List.of(new AuditEntryResponse(Instant.now(), "INITIATED", "op", null, "ACTIVE", "d"))));

        StepVerifier.create(controller.initiateConnection(tenantHeader, EXECUTOR, "AGENT", initiate)).assertNext(r -> assertEquals(HttpStatus.CREATED, r.getStatus())).verifyComplete();
        StepVerifier.create(controller.retrieveConnection(id, tenantHeader, "AGENT")).assertNext(r -> assertEquals(HttpStatus.OK, r.getStatus())).verifyComplete();
        StepVerifier.create(controller.retrieveConnections(tenantHeader, "AGENT")).assertNext(list -> assertEquals(1, list.size())).verifyComplete();
        StepVerifier.create(controller.updateConnection(id, tenantHeader, EXECUTOR, "AGENT", update)).assertNext(r -> assertEquals(HttpStatus.NO_CONTENT, r.getStatus())).verifyComplete();
        StepVerifier.create(controller.enableConnection(id, tenantHeader, EXECUTOR, "AGENT")).assertNext(r -> assertEquals(HttpStatus.NO_CONTENT, r.getStatus())).verifyComplete();
        StepVerifier.create(controller.disableConnection(id, tenantHeader, EXECUTOR, "AGENT")).assertNext(r -> assertEquals(HttpStatus.NO_CONTENT, r.getStatus())).verifyComplete();
        StepVerifier.create(controller.checkConnection(id, tenantHeader, "AGENT")).assertNext(r -> assertEquals(HttpStatus.OK, r.getStatus())).verifyComplete();
        StepVerifier.create(controller.connectionAuditLog(id, tenantHeader, "AGENT")).assertNext(list -> assertEquals(1, list.size())).verifyComplete();
    }

    @Test
    @DisplayName("link routes delegate with the tenant, the executor and the role, and the filters of the list travel together")
    void linkRoutes() {
        var request = new InitiateTicketLinkRequest(UUID.randomUUID(), SubjectType.INCIDENT, UUID.randomUUID());
        UUID connectionId = UUID.randomUUID();
        UUID subjectId = UUID.randomUUID();
        var filter = new TicketLinkRepository.Filter(connectionId, SubjectType.PROBLEM, subjectId, LinkStatus.LINKED);
        when(initiateLink.execute(tenant, request, EXECUTOR, "AGENT")).thenReturn(Mono.just(linkResponse()));
        when(retrieveLink.execute(id, tenant, "AGENT")).thenReturn(Mono.just(linkResponse()));
        when(retrieveLinks.execute(tenant, filter, "AGENT")).thenReturn(Flux.just(linkResponse()));
        when(syncLink.execute(id, tenant, EXECUTOR, "AGENT")).thenReturn(Mono.just(linkResponse()));
        when(detachLink.execute(id, tenant, EXECUTOR, "AGENT")).thenReturn(Mono.empty());
        when(linkAuditLog.execute(id, tenant, "AGENT")).thenReturn(Mono.just(List.of()));

        StepVerifier.create(controller.initiate(tenantHeader, EXECUTOR, "AGENT", request)).assertNext(r -> assertEquals(HttpStatus.CREATED, r.getStatus())).verifyComplete();
        StepVerifier.create(controller.retrieve(id, tenantHeader, "AGENT")).assertNext(r -> assertEquals(HttpStatus.OK, r.getStatus())).verifyComplete();
        StepVerifier.create(controller.retrieveAll(tenantHeader, "AGENT", connectionId, SubjectType.PROBLEM, subjectId, LinkStatus.LINKED)).assertNext(list -> assertEquals(1, list.size())).verifyComplete();
        StepVerifier.create(controller.sync(id, tenantHeader, EXECUTOR, "AGENT")).assertNext(r -> assertEquals(HttpStatus.OK, r.getStatus())).verifyComplete();
        StepVerifier.create(controller.detach(id, tenantHeader, EXECUTOR, "AGENT")).assertNext(r -> assertEquals(HttpStatus.NO_CONTENT, r.getStatus())).verifyComplete();
        StepVerifier.create(controller.retrieveAuditLog(id, tenantHeader, "AGENT")).assertNext(list -> assertEquals(0, list.size())).verifyComplete();
    }

    @Test
    @DisplayName("the webhook route needs no tenant and no role: the connection in the path and the token header are all it forwards")
    void webhook() {
        Map<String, Object> payload = Map.of("k", "v");
        when(receiveWebhook.execute(eq(id), eq("tok"), any())).thenReturn(Mono.just(new WebhookResultResponse(WebhookResultResponse.APPLIED, "ok")));

        StepVerifier.create(controller.receiveWebhook(id, "tok", payload)).assertNext(r -> assertEquals(HttpStatus.OK, r.getStatus())).verifyComplete();

        verify(receiveWebhook).execute(id, "tok", payload);
    }
}
