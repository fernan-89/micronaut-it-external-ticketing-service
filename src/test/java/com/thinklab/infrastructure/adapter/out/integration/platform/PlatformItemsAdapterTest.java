package com.thinklab.infrastructure.adapter.out.integration.platform;

import com.thinklab.domain.exception.PlatformActionRefusedException;
import com.thinklab.domain.exception.PlatformItemNotFoundException;
import com.thinklab.domain.model.Connection.InboundAction;
import com.thinklab.domain.model.TicketLink.SubjectType;
import com.thinklab.infrastructure.adapter.out.integration.platform.PlatformItemsAdapter.CommentApiResponse;
import com.thinklab.infrastructure.adapter.out.integration.platform.PlatformItemsAdapter.ItemApiResponse;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformItemsAdapterTest {

    private static final String EXECUTOR = "external-ticketing:c1";

    private final UUID org = UUID.randomUUID();
    private final UUID id = UUID.randomUUID();
    private final String tenant = org.toString();
    private IncidentApiClient incidents;
    private ServiceRequestApiClient requests;
    private ProblemApiClient problems;
    private PlatformItemsAdapter adapter;

    @BeforeEach
    void setUp() {
        incidents = mock(IncidentApiClient.class);
        requests = mock(ServiceRequestApiClient.class);
        problems = mock(ProblemApiClient.class);
        adapter = new PlatformItemsAdapter(incidents, requests, problems);
    }

    private static HttpClientResponseException http(HttpStatus status, Map<String, Object> body) {
        var response = body == null ? HttpResponse.status(status) : HttpResponse.status(status).body(body);
        return new HttpClientResponseException("x", response);
    }

    @Test
    @DisplayName("an incident is read with its title, description and comments, with the internal flag as it is")
    void retrieveIncident() {
        UUID commentId = UUID.randomUUID();
        when(incidents.retrieve(id, tenant, EXECUTOR)).thenReturn(Mono.just(new ItemApiResponse("Printer down", "On fire", "NEW", null, null,
                List.of(new CommentApiResponse(commentId, "agent", "hi", true), new CommentApiResponse(UUID.randomUUID(), "agent", "public", false)))));

        StepVerifier.create(adapter.retrieve(SubjectType.INCIDENT, id, org, EXECUTOR))
                .assertNext(item -> {
                    assertEquals("Printer down", item.title());
                    assertEquals("NEW", item.status());
                    assertTrue(item.comments().get(0).internal());
                    assertFalse(item.comments().get(1).internal());
                    assertEquals(commentId, item.comments().get(0).id());
                }).verifyComplete();
    }

    @Test
    @DisplayName("a request has no title or description: its catalog item stands in, and its answers never leave")
    void retrieveRequest() {
        when(requests.retrieve(id, tenant, EXECUTOR))
                .thenReturn(Mono.just(new ItemApiResponse(null, null, "SUBMITTED", "New laptop", "LAP-1", null)))
                .thenReturn(Mono.just(new ItemApiResponse(null, null, "SUBMITTED", "New laptop", null, List.of())));

        StepVerifier.create(adapter.retrieve(SubjectType.SERVICE_REQUEST, id, org, EXECUTOR))
                .assertNext(item -> {
                    assertEquals("New laptop", item.title());
                    assertEquals("Service request for New laptop (LAP-1).", item.description());
                    assertTrue(item.comments().isEmpty());
                }).verifyComplete();
        StepVerifier.create(adapter.retrieve(SubjectType.SERVICE_REQUEST, id, org, EXECUTOR))
                .assertNext(item -> assertEquals("Service request for New laptop.", item.description())).verifyComplete();
    }

    @Test
    @DisplayName("a problem comment has no public flag, so it is treated as an internal note and never pushed")
    void retrieveProblem() {
        when(problems.retrieve(id, tenant, EXECUTOR)).thenReturn(Mono.just(new ItemApiResponse("Switch", "Drops", "NEW", null, null,
                List.of(new CommentApiResponse(UUID.randomUUID(), "analyst", "root cause is X", false)))));

        StepVerifier.create(adapter.retrieve(SubjectType.PROBLEM, id, org, EXECUTOR))
                .assertNext(item -> assertTrue(item.comments().get(0).internal())).verifyComplete();
    }

    @Test
    @DisplayName("an incident takes every inbound action through its own control route")
    void incidentActions() {
        when(incidents.acknowledge(id, tenant, EXECUTOR)).thenReturn(Mono.empty());
        when(incidents.start(id, tenant, EXECUTOR)).thenReturn(Mono.empty());
        when(incidents.resume(id, tenant, EXECUTOR)).thenReturn(Mono.empty());
        when(incidents.close(id, tenant, EXECUTOR)).thenReturn(Mono.empty());
        when(incidents.cancel(id, tenant, EXECUTOR)).thenReturn(Mono.empty());
        when(incidents.resolve(eq(id), eq(tenant), eq(EXECUTOR), any())).thenReturn(Mono.empty());

        for (InboundAction action : InboundAction.values()) {
            StepVerifier.create(adapter.applyAction(SubjectType.INCIDENT, id, org, action, "note", EXECUTOR)).verifyComplete();
        }

        verify(incidents).resolve(id, tenant, EXECUTOR, new PlatformItemsAdapter.IncidentResolveApiRequest("RESOLVED_EXTERNALLY", "note"));
        verify(incidents).acknowledge(id, tenant, EXECUTOR);
    }

    @Test
    @DisplayName("a request takes start, resolve (fulfil), close and cancel; the others are refused without a call")
    void requestActions() {
        when(requests.startFulfilment(id, tenant, EXECUTOR)).thenReturn(Mono.empty());
        when(requests.close(id, tenant, EXECUTOR)).thenReturn(Mono.empty());
        when(requests.cancel(id, tenant, EXECUTOR)).thenReturn(Mono.empty());
        when(requests.fulfil(eq(id), eq(tenant), eq(EXECUTOR), any())).thenReturn(Mono.empty());

        for (InboundAction action : List.of(InboundAction.START, InboundAction.RESOLVE, InboundAction.CLOSE, InboundAction.CANCEL)) {
            StepVerifier.create(adapter.applyAction(SubjectType.SERVICE_REQUEST, id, org, action, "n", EXECUTOR)).verifyComplete();
        }
        for (InboundAction action : List.of(InboundAction.ACKNOWLEDGE, InboundAction.RESUME)) {
            StepVerifier.create(adapter.applyAction(SubjectType.SERVICE_REQUEST, id, org, action, "n", EXECUTOR)).expectError(PlatformActionRefusedException.class).verify();
        }
        verify(requests).fulfil(id, tenant, EXECUTOR, new PlatformItemsAdapter.NotesApiRequest("n"));
    }

    @Test
    @DisplayName("a problem takes start (investigate), resolve, close and cancel; the others are refused without a call")
    void problemActions() {
        when(problems.investigate(id, tenant, EXECUTOR)).thenReturn(Mono.empty());
        when(problems.close(id, tenant, EXECUTOR)).thenReturn(Mono.empty());
        when(problems.cancel(id, tenant, EXECUTOR)).thenReturn(Mono.empty());
        when(problems.resolve(eq(id), eq(tenant), eq(EXECUTOR), any())).thenReturn(Mono.empty());

        for (InboundAction action : List.of(InboundAction.START, InboundAction.RESOLVE, InboundAction.CLOSE, InboundAction.CANCEL)) {
            StepVerifier.create(adapter.applyAction(SubjectType.PROBLEM, id, org, action, "n", EXECUTOR)).verifyComplete();
        }
        for (InboundAction action : List.of(InboundAction.ACKNOWLEDGE, InboundAction.RESUME)) {
            StepVerifier.create(adapter.applyAction(SubjectType.PROBLEM, id, org, action, "n", EXECUTOR)).expectError(PlatformActionRefusedException.class).verify();
        }
        verify(problems).resolve(id, tenant, EXECUTOR, new PlatformItemsAdapter.ProblemResolveApiRequest("n"));
    }

    @Test
    @DisplayName("an imported comment is an internal note on an incident or a request, and a plain note on a problem")
    void comments() {
        when(incidents.comment(eq(id), eq(tenant), eq(EXECUTOR), any())).thenReturn(Mono.empty());
        when(requests.comment(eq(id), eq(tenant), eq(EXECUTOR), any())).thenReturn(Mono.empty());
        when(problems.comment(eq(id), eq(tenant), eq(EXECUTOR), any())).thenReturn(Mono.empty());

        StepVerifier.create(adapter.addComment(SubjectType.INCIDENT, id, org, "t", EXECUTOR)).verifyComplete();
        StepVerifier.create(adapter.addComment(SubjectType.SERVICE_REQUEST, id, org, "t", EXECUTOR)).verifyComplete();
        StepVerifier.create(adapter.addComment(SubjectType.PROBLEM, id, org, "t", EXECUTOR)).verifyComplete();

        verify(incidents).comment(id, tenant, EXECUTOR, new PlatformItemsAdapter.CommentApiRequest("t", true));
        verify(requests).comment(id, tenant, EXECUTOR, new PlatformItemsAdapter.CommentApiRequest("t", true));
        verify(problems).comment(id, tenant, EXECUTOR, new PlatformItemsAdapter.ProblemCommentApiRequest("t"));
    }

    @Test
    @DisplayName("a 404 is a missing item, another 4xx is the item refusing (with its reason), a 5xx stays a failure of the dependency")
    void errors() {
        when(incidents.retrieve(id, tenant, EXECUTOR))
                .thenReturn(Mono.error(http(HttpStatus.NOT_FOUND, null)));
        StepVerifier.create(adapter.retrieve(SubjectType.INCIDENT, id, org, EXECUTOR)).expectError(PlatformItemNotFoundException.class).verify();

        when(incidents.close(id, tenant, EXECUTOR))
                .thenReturn(Mono.error(http(HttpStatus.CONFLICT, Map.of("detail", "Illegal transition: Incident is NEW"))))
                .thenReturn(Mono.error(http(HttpStatus.BAD_REQUEST, null)))
                .thenReturn(Mono.error(http(HttpStatus.BAD_GATEWAY, null)));
        StepVerifier.create(adapter.applyAction(SubjectType.INCIDENT, id, org, InboundAction.CLOSE, "n", EXECUTOR))
                .expectErrorMessage("Illegal transition: Incident is NEW").verify();
        StepVerifier.create(adapter.applyAction(SubjectType.INCIDENT, id, org, InboundAction.CLOSE, "n", EXECUTOR))
                .expectErrorMessage("refused (HTTP 400)").verify();
        StepVerifier.create(adapter.applyAction(SubjectType.INCIDENT, id, org, InboundAction.CLOSE, "n", EXECUTOR)).expectError(IllegalStateException.class).verify();

        when(problems.comment(eq(id), eq(tenant), eq(EXECUTOR), any())).thenReturn(Mono.error(http(HttpStatus.NOT_FOUND, null)));
        StepVerifier.create(adapter.addComment(SubjectType.PROBLEM, id, org, "t", EXECUTOR)).expectError(PlatformItemNotFoundException.class).verify();
    }
}
