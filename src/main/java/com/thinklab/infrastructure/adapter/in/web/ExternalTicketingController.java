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
import com.thinklab.domain.model.TicketLink.LinkStatus;
import com.thinklab.domain.model.TicketLink.SubjectType;
import com.thinklab.domain.repository.TicketLinkRepository;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.annotation.QueryValue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Inbound Web Adapter for the {@code it-external-ticketing} Service Domain.
 *
 * <p><b>BIAN-Aligned Resource Model (ADR-013):</b> {@link com.thinklab.domain.model.TicketLink} is the Control Record, at the root;
 * {@link com.thinklab.domain.model.Connection} is the secondary aggregate, under {@code /connection}. There is no {@code DELETE}: a
 * connection is disabled and a link is detached.
 *
 * <p><b>Tenant and staff (ADR-032):</b> every route needs {@code X-Tenant-Id} and refuses a {@code REQUESTER} (403 {@code ERR-ETK-00403}),
 * except the webhook: the provider cannot send those headers, so {@code POST /webhook/{connectionId}/receive} is identified by the
 * connection in the path and proved by the {@code X-Webhook-Token} header (ADR-032).
 */
@Controller("/it-external-ticketing/v1")
public class ExternalTicketingController {

    private static final Logger log = LoggerFactory.getLogger(ExternalTicketingController.class);
    static final String TENANT_HEADER = "X-Tenant-Id";
    static final String EXECUTOR_HEADER = "X-Executor";
    static final String ROLE_HEADER = "X-Role";
    static final String WEBHOOK_TOKEN_HEADER = "X-Webhook-Token";

    private final InitiateConnectionUseCase initiateConnection;
    private final RetrieveConnectionUseCase retrieveConnection;
    private final RetrieveConnectionsUseCase retrieveConnections;
    private final UpdateConnectionUseCase updateConnection;
    private final ControlConnectionUseCase controlConnection;
    private final CheckConnectionUseCase checkConnection;
    private final RetrieveConnectionAuditLogUseCase connectionAuditLog;
    private final InitiateTicketLinkUseCase initiateLink;
    private final RetrieveTicketLinkUseCase retrieveLink;
    private final RetrieveTicketLinksUseCase retrieveLinks;
    private final SyncTicketLinkUseCase syncLink;
    private final DetachTicketLinkUseCase detachLink;
    private final RetrieveTicketLinkAuditLogUseCase linkAuditLog;
    private final ReceiveWebhookUseCase receiveWebhook;

    public ExternalTicketingController(
            InitiateConnectionUseCase initiateConnection,
            RetrieveConnectionUseCase retrieveConnection,
            RetrieveConnectionsUseCase retrieveConnections,
            UpdateConnectionUseCase updateConnection,
            ControlConnectionUseCase controlConnection,
            CheckConnectionUseCase checkConnection,
            RetrieveConnectionAuditLogUseCase connectionAuditLog,
            InitiateTicketLinkUseCase initiateLink,
            RetrieveTicketLinkUseCase retrieveLink,
            RetrieveTicketLinksUseCase retrieveLinks,
            SyncTicketLinkUseCase syncLink,
            DetachTicketLinkUseCase detachLink,
            RetrieveTicketLinkAuditLogUseCase linkAuditLog,
            ReceiveWebhookUseCase receiveWebhook
    ) {
        this.initiateConnection = initiateConnection;
        this.retrieveConnection = retrieveConnection;
        this.retrieveConnections = retrieveConnections;
        this.updateConnection = updateConnection;
        this.controlConnection = controlConnection;
        this.checkConnection = checkConnection;
        this.connectionAuditLog = connectionAuditLog;
        this.initiateLink = initiateLink;
        this.retrieveLink = retrieveLink;
        this.retrieveLinks = retrieveLinks;
        this.syncLink = syncLink;
        this.detachLink = detachLink;
        this.linkAuditLog = linkAuditLog;
        this.receiveWebhook = receiveWebhook;
    }

    // ------------------------------------------------------------------ Connection

    /** Behavior Qualifier: {@code connection/initiate}. Registers a ServiceNow or Jira instance; it names the environment variables that hold the secrets, never the secrets. */
    @Post("/connection/initiate")
    public Mono<HttpResponse<ConnectionResponse>> initiateConnection(
            @Header(TENANT_HEADER) @NotBlank String tenantId,
            @Header(EXECUTOR_HEADER) @NotBlank String executor,
            @Header(ROLE_HEADER) @Nullable String role,
            @Body @Valid InitiateConnectionRequest request
    ) {
        log.info("[ACTION: INITIATE_CONNECTION] [EXECUTOR: {}] Received request for organisation: {}", executor, tenantId);

        return initiateConnection.execute(UUID.fromString(tenantId), request, executor, role).map(HttpResponse::created);
    }

    /** Behavior Qualifier: {@code connection/retrieve}. One Connection of the tenant. */
    @Get("/connection/{id}/retrieve")
    public Mono<HttpResponse<ConnectionResponse>> retrieveConnection(
            @PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(ROLE_HEADER) @Nullable String role
    ) {
        return Mono.defer(() -> retrieveConnection.execute(id, UUID.fromString(tenantId), role)).map(HttpResponse::ok);
    }

    /** Behavior Qualifier: {@code connection/retrieve} (collection). */
    @Get("/connection/retrieve")
    public Mono<List<ConnectionResponse>> retrieveConnections(@Header(TENANT_HEADER) @NotBlank String tenantId, @Header(ROLE_HEADER) @Nullable String role) {
        return Mono.defer(() -> retrieveConnections.execute(UUID.fromString(tenantId), role).collectList());
    }

    /** Behavior Qualifier: {@code connection/update}. Everything but the provider. */
    @Put("/connection/{id}/update")
    public Mono<HttpResponse<Void>> updateConnection(
            @PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(EXECUTOR_HEADER) @NotBlank String executor,
            @Header(ROLE_HEADER) @Nullable String role, @Body @Valid UpdateConnectionRequest request
    ) {
        return Mono.defer(() -> updateConnection.execute(id, UUID.fromString(tenantId), request, executor, role)).thenReturn(HttpResponse.noContent());
    }

    /** Behavior Qualifier: {@code connection/control/enable}. DISABLED -&gt; ACTIVE. */
    @Put("/connection/{id}/control/enable")
    public Mono<HttpResponse<Void>> enableConnection(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId,
                                                     @Header(EXECUTOR_HEADER) @NotBlank String executor, @Header(ROLE_HEADER) @Nullable String role) {
        return control(id, tenantId, ControlConnectionUseCase.Action.ENABLE, executor, role);
    }

    /** Behavior Qualifier: {@code connection/control/disable}. ACTIVE -&gt; DISABLED: nothing is sent and no webhook is accepted. */
    @Put("/connection/{id}/control/disable")
    public Mono<HttpResponse<Void>> disableConnection(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId,
                                                      @Header(EXECUTOR_HEADER) @NotBlank String executor, @Header(ROLE_HEADER) @Nullable String role) {
        return control(id, tenantId, ControlConnectionUseCase.Action.DISABLE, executor, role);
    }

    /** Behavior Qualifier: {@code connection/check/execute}. Are the secrets set, and does the provider answer with them. Never fails because the provider is down. */
    @Put("/connection/{id}/check/execute")
    public Mono<HttpResponse<ConnectionCheckResponse>> checkConnection(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId,
                                                                       @Header(ROLE_HEADER) @Nullable String role) {
        return Mono.defer(() -> checkConnection.execute(id, UUID.fromString(tenantId), role)).map(HttpResponse::ok);
    }

    /** Behavior Qualifier: {@code connection/audit-log/retrieve}. */
    @Get("/connection/{id}/audit-log/retrieve")
    public Mono<List<AuditEntryResponse>> connectionAuditLog(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId,
                                                             @Header(ROLE_HEADER) @Nullable String role) {
        return Mono.defer(() -> connectionAuditLog.execute(id, UUID.fromString(tenantId), role));
    }

    // ------------------------------------------------------------------ TicketLink

    /** Behavior Qualifier: {@code initiate}. Links an incident, request or problem to a new ticket at the provider and pushes it. */
    @Post("/initiate")
    public Mono<HttpResponse<TicketLinkResponse>> initiate(
            @Header(TENANT_HEADER) @NotBlank String tenantId,
            @Header(EXECUTOR_HEADER) @NotBlank String executor,
            @Header(ROLE_HEADER) @Nullable String role,
            @Body @Valid InitiateTicketLinkRequest request
    ) {
        log.info("[ACTION: INITIATE_TICKET_LINK] [EXECUTOR: {}] Received request for organisation: {}", executor, tenantId);

        return initiateLink.execute(UUID.fromString(tenantId), request, executor, role).map(HttpResponse::created);
    }

    /** Behavior Qualifier: {@code retrieve}. One TicketLink. */
    @Get("/{id}/retrieve")
    public Mono<HttpResponse<TicketLinkResponse>> retrieve(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId,
                                                           @Header(ROLE_HEADER) @Nullable String role) {
        return Mono.defer(() -> retrieveLink.execute(id, UUID.fromString(tenantId), role)).map(HttpResponse::ok);
    }

    /** Behavior Qualifier: {@code retrieve} (collection). {@code subjectId} answers "is this item linked, and where". */
    @Get("/retrieve")
    public Mono<List<TicketLinkResponse>> retrieveAll(
            @Header(TENANT_HEADER) @NotBlank String tenantId,
            @Header(ROLE_HEADER) @Nullable String role,
            @QueryValue @Nullable UUID connectionId,
            @QueryValue @Nullable SubjectType subjectType,
            @QueryValue @Nullable UUID subjectId,
            @QueryValue @Nullable LinkStatus status
    ) {
        return Mono.defer(() -> retrieveLinks.execute(UUID.fromString(tenantId), new TicketLinkRepository.Filter(connectionId, subjectType, subjectId, status), role)
                .collectList());
    }

    /** Behavior Qualifier: {@code sync/execute}. Retries a ticket that was never created, or pushes the status and public comments that changed. */
    @Put("/{id}/sync/execute")
    public Mono<HttpResponse<TicketLinkResponse>> sync(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId,
                                                       @Header(EXECUTOR_HEADER) @NotBlank String executor, @Header(ROLE_HEADER) @Nullable String role) {
        return Mono.defer(() -> syncLink.execute(id, UUID.fromString(tenantId), executor, role)).map(HttpResponse::ok);
    }

    /** Behavior Qualifier: {@code control/detach}. Terminal; the ticket at the provider is left as it is. */
    @Put("/{id}/control/detach")
    public Mono<HttpResponse<Void>> detach(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId,
                                           @Header(EXECUTOR_HEADER) @NotBlank String executor, @Header(ROLE_HEADER) @Nullable String role) {
        return Mono.defer(() -> detachLink.execute(id, UUID.fromString(tenantId), executor, role)).thenReturn(HttpResponse.noContent());
    }

    /** Behavior Qualifier: {@code audit-log/retrieve}. */
    @Get("/{id}/audit-log/retrieve")
    public Mono<List<AuditEntryResponse>> retrieveAuditLog(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId,
                                                           @Header(ROLE_HEADER) @Nullable String role) {
        return Mono.defer(() -> linkAuditLog.execute(id, UUID.fromString(tenantId), role));
    }

    /** Behavior Qualifier: {@code webhook/receive}. What the provider reports; no tenant header, the token proves the caller (see the class comment). */
    @Post("/webhook/{connectionId}/receive")
    public Mono<HttpResponse<WebhookResultResponse>> receiveWebhook(
            @PathVariable UUID connectionId,
            @Header(WEBHOOK_TOKEN_HEADER) @Nullable String token,
            @Body Map<String, Object> payload
    ) {
        log.info("[ACTION: RECEIVE_WEBHOOK] Received an event for Connection ID: {}", connectionId);

        return Mono.defer(() -> receiveWebhook.execute(connectionId, token, payload)).map(HttpResponse::ok);
    }

    private Mono<HttpResponse<Void>> control(UUID id, String tenantId, ControlConnectionUseCase.Action action, String executor, String role) {
        log.info("[ACTION: CONTROL_CONNECTION] [EXECUTOR: {}] {} for ID: {}", executor, action, id);

        return Mono.defer(() -> controlConnection.execute(id, UUID.fromString(tenantId), action, executor, role)).thenReturn(HttpResponse.noContent());
    }
}
