package com.thinklab.infrastructure.adapter.out.integration.ticketing;

import com.thinklab.domain.exception.ExternalTicketingException;
import com.thinklab.domain.model.Connection;
import com.thinklab.domain.model.Connection.Provider;
import com.thinklab.domain.model.TicketLink.SubjectType;
import com.thinklab.domain.port.ExternalTicketingPort.ExternalEvent;
import com.thinklab.domain.port.ExternalTicketingPort.TicketDraft;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings({"unchecked", "rawtypes"})
class TicketingAdaptersTest {

    private final UUID org = UUID.randomUUID();
    private final UUID platformRef = UUID.randomUUID();

    // ------------------------------------------------------------------ A fake ProviderHttp: routes by "METHOD url", records every call

    /** What the adapter asked, and the answers we give back by route. */
    private static final class Wire {
        final Map<String, Function<Object, Mono<Map<String, Object>>>> routes = new LinkedHashMap<>();
        final List<String> calls = new ArrayList<>();
        final List<Object> bodies = new ArrayList<>();
        final ProviderHttp http = mock(ProviderHttp.class);

        Wire() {
            when(http.call(any(), any(), any(), any(), any(), any())).thenAnswer(call -> {
                String route = call.getArgument(2) + " " + call.getArgument(3);
                calls.add(route);
                bodies.add(call.getArgument(5));
                Function<Object, Mono<Map<String, Object>>> answer = routes.get(route);
                return answer == null ? Mono.error(new IllegalStateException("no route " + route)) : answer.apply(call.getArgument(5));
            });
        }

        Wire on(String route, Map<String, Object> answer) {
            routes.put(route, body -> Mono.just(answer));
            return this;
        }
    }

    private Connection jira() {
        return Connection.createNew(UUID.randomUUID(), org, "Jira", Provider.JIRA, "https://acme.atlassian.net/", "JIRA_AUTH", "JIRA_HOOK", "svc", "ITSM", null, null, "op");
    }

    private Connection snow() {
        return Connection.createNew(UUID.randomUUID(), org, "SNOW", Provider.SERVICENOW, "https://acme.service-now.com", "SNOW_AUTH", "SNOW_HOOK", "svc", null, null, null, "op");
    }

    private TicketDraft draft(SubjectType type, String status) {
        return new TicketDraft(type, "Printer down", "It is on fire", status, platformRef);
    }

    // ------------------------------------------------------------------ ProviderHttp

    private HttpClient client;
    private ProviderHttp realHttp;

    @BeforeEach
    void setUp() {
        client = mock(HttpClient.class);
        realHttp = new ProviderHttp(client);
    }

    @Test
    @DisplayName("ProviderHttp sends the Authorization header and the JSON body, and returns the answer as a map")
    void httpSends() {
        when(client.exchange(any(HttpRequest.class), any(Argument.class))).thenReturn(Mono.just(HttpResponse.ok(Map.of("key", "ITSM-1"))));

        StepVerifier.create(realHttp.call("Jira", "creating", HttpMethod.POST, "https://acme/rest", "Basic abc", Map.of("a", "b")))
                .assertNext(body -> assertEquals("ITSM-1", body.get("key"))).verifyComplete();

        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).exchange(request.capture(), any(Argument.class));
        assertEquals("Basic abc", request.getValue().getHeaders().get("Authorization"));
        assertEquals(HttpMethod.POST, request.getValue().getMethod());
        assertEquals(Map.of("a", "b"), request.getValue().getBody().orElseThrow());
        assertEquals("application/json", request.getValue().getContentType().orElseThrow().toString());
    }

    @Test
    @DisplayName("ProviderHttp sends no body and no content type on a GET, and an empty answer is an empty map")
    void httpGetNoBody() {
        when(client.exchange(any(HttpRequest.class), any(Argument.class))).thenReturn(Mono.just(HttpResponse.noContent()));

        StepVerifier.create(realHttp.call("Jira", "reading", HttpMethod.GET, "https://acme/rest", "Basic abc", null))
                .assertNext(body -> assertTrue(body.isEmpty())).verifyComplete();

        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).exchange(request.capture(), any(Argument.class));
        assertTrue(request.getValue().getBody().isEmpty());
    }

    @Test
    @DisplayName("a provider refusal names the operation and the status, never the response body; an unreachable provider says so")
    void httpErrors() {
        HttpResponse<Object> unauthorized = HttpResponse.status(HttpStatus.UNAUTHORIZED).body(Map.of("secret", "echoed-credentials"));
        when(client.exchange(any(HttpRequest.class), any(Argument.class)))
                .thenReturn(Mono.error(new HttpClientResponseException("boom echoed-credentials", unauthorized)))
                .thenReturn(Mono.error(new IllegalStateException("connect refused echoed-credentials")))
                .thenReturn(Mono.error(new ExternalTicketingException("already ours")));

        StepVerifier.create(realHttp.call("Jira", "creating the issue", HttpMethod.POST, "u", "a", Map.of()))
                .expectErrorSatisfies(error -> {
                    assertTrue(error instanceof ExternalTicketingException);
                    assertEquals("Jira refused creating the issue (HTTP 401).", error.getMessage());
                    assertFalse(error.getMessage().contains("echoed"));
                }).verify();
        StepVerifier.create(realHttp.call("Jira", "reading", HttpMethod.GET, "u", "a", null))
                .expectErrorSatisfies(error -> {
                    assertEquals("Jira could not be reached for reading.", error.getMessage());
                    assertFalse(error.getMessage().contains("echoed"));
                }).verify();
        StepVerifier.create(realHttp.call("Jira", "reading", HttpMethod.GET, "u", "a", null)).expectErrorMessage("already ours").verify();
    }

    // ------------------------------------------------------------------ Jira

    @Test
    @DisplayName("Jira: a ticket is an issue of the project, typed by the item, and its id is the issue key")
    void jiraCreate() {
        Wire wire = new Wire().on("POST https://acme.atlassian.net/rest/api/2/issue", Map.of("key", "ITSM-1"));
        JiraTicketingAdapter adapter = new JiraTicketingAdapter(wire.http);
        assertEquals(Provider.JIRA, adapter.provider());

        for (SubjectType type : SubjectType.values()) {
            StepVerifier.create(adapter.createTicket(jira(), "Basic abc", draft(type, null)))
                    .assertNext(ticket -> {
                        assertEquals("ITSM-1", ticket.externalId());
                        assertEquals("https://acme.atlassian.net/browse/ITSM-1", ticket.url());
                    }).verifyComplete();
        }
        Map<String, Object> fields = (Map<String, Object>) ((Map<String, Object>) wire.bodies.get(0)).get("fields");
        assertEquals(Map.of("key", "ITSM"), fields.get("project"));
        assertEquals(Map.of("name", "Incident"), fields.get("issuetype"));
        assertEquals(Map.of("name", "Service Request"), ((Map<String, Object>) ((Map<String, Object>) wire.bodies.get(1)).get("fields")).get("issuetype"));
        assertEquals(Map.of("name", "Problem"), ((Map<String, Object>) ((Map<String, Object>) wire.bodies.get(2)).get("fields")).get("issuetype"));
        assertTrue(fields.get("description").toString().contains(platformRef.toString()));
    }

    @Test
    @DisplayName("Jira: a long title is cut, and an answer without an issue key is an error")
    void jiraCreateEdges() {
        Wire wire = new Wire().on("POST https://acme.atlassian.net/rest/api/2/issue", Map.of());
        JiraTicketingAdapter adapter = new JiraTicketingAdapter(wire.http);

        StepVerifier.create(adapter.createTicket(jira(), "a", draft(SubjectType.INCIDENT, null))).expectError(ExternalTicketingException.class).verify();
        wire.on("POST https://acme.atlassian.net/rest/api/2/issue", Map.of("key", " "));
        StepVerifier.create(adapter.createTicket(jira(), "a", draft(SubjectType.INCIDENT, null))).expectError(ExternalTicketingException.class).verify();

        wire.on("POST https://acme.atlassian.net/rest/api/2/issue", Map.of("key", "ITSM-2"));
        StepVerifier.create(adapter.createTicket(jira(), "a", new TicketDraft(SubjectType.INCIDENT, "t".repeat(400), "d", null, platformRef))).expectNextCount(1).verifyComplete();
        Map<String, Object> fields = (Map<String, Object>) ((Map<String, Object>) wire.bodies.get(2)).get("fields");
        assertEquals(250, fields.get("summary").toString().length());
    }

    @Test
    @DisplayName("Jira: a status is reached through the transition that ends in it; creation with a status moves the new issue")
    void jiraStatus() {
        String issue = "https://acme.atlassian.net/rest/api/2/issue/ITSM-1";
        Wire wire = new Wire()
                .on("POST https://acme.atlassian.net/rest/api/2/issue", Map.of("key", "ITSM-1"))
                .on("GET " + issue + "?fields=status", Map.of("fields", Map.of("status", Map.of("name", "To Do"))))
                .on("GET " + issue + "/transitions", Map.of("transitions", List.of(
                        Map.of("id", "11", "to", Map.of("name", "Done")), Map.of("id", "21", "to", Map.of("name", "In Progress")), "not-a-map")))
                .on("POST " + issue + "/transitions", Map.of());
        JiraTicketingAdapter adapter = new JiraTicketingAdapter(wire.http);

        StepVerifier.create(adapter.createTicket(jira(), "a", draft(SubjectType.INCIDENT, "in progress"))).expectNextCount(1).verifyComplete();

        assertEquals(Map.of("transition", Map.of("id", "21")), wire.bodies.get(wire.bodies.size() - 1));
    }

    @Test
    @DisplayName("Jira: an issue already in the wanted status is left alone; a status with no transition is an error")
    void jiraStatusEdges() {
        String issue = "https://acme.atlassian.net/rest/api/2/issue/ITSM-1";
        Wire wire = new Wire().on("GET " + issue + "?fields=status", Map.of("fields", Map.of("status", Map.of("name", "Done"))));
        JiraTicketingAdapter adapter = new JiraTicketingAdapter(wire.http);

        StepVerifier.create(adapter.updateStatus(jira(), "a", "ITSM-1", "done")).verifyComplete();
        assertEquals(1, wire.calls.size());

        wire.on("GET " + issue + "?fields=status", Map.of());
        wire.on("GET " + issue + "/transitions", Map.of("transitions", List.of(Map.of("id", "1", "to", Map.of("name", "Other")))));
        StepVerifier.create(adapter.updateStatus(jira(), "a", "ITSM-1", "Done")).expectErrorMessage("Jira has no transition from the current status to 'Done'.").verify();

        wire.on("GET " + issue + "/transitions", Map.of("transitions", "none"));
        StepVerifier.create(adapter.updateStatus(jira(), "a", "ITSM-1", "Done")).expectError(ExternalTicketingException.class).verify();
        wire.on("GET " + issue + "/transitions", Map.of());
        StepVerifier.create(adapter.updateStatus(jira(), "a", "ITSM-1", "Done")).expectError(ExternalTicketingException.class).verify();
    }

    @Test
    @DisplayName("Jira: comments are posted as the issue's comment body, and ping reads the authenticated user")
    void jiraCommentAndPing() {
        Wire wire = new Wire()
                .on("POST https://acme.atlassian.net/rest/api/2/issue/ITSM-1/comment", Map.of())
                .on("GET https://acme.atlassian.net/rest/api/2/myself", Map.of("name", "bot"));
        JiraTicketingAdapter adapter = new JiraTicketingAdapter(wire.http);

        StepVerifier.create(adapter.addComment(jira(), "a", "ITSM-1", "hello")).verifyComplete();
        StepVerifier.create(adapter.ping(jira(), "a")).verifyComplete();

        assertEquals(Map.of("body", "hello"), wire.bodies.get(0));
    }

    @Test
    @DisplayName("Jira: a webhook payload is read into one event: key, status of the changelog, comment, and who did it")
    void jiraWebhook() {
        JiraTicketingAdapter adapter = new JiraTicketingAdapter(new Wire().http);
        Map<String, Object> payload = new HashMap<>();
        payload.put("webhookEvent", "jira:issue_updated");
        payload.put("timestamp", 1700000000L);
        payload.put("issue", Map.of("key", "ITSM-1"));
        payload.put("user", Map.of("accountId", "acc-1", "name", "bob"));
        payload.put("changelog", Map.of("items", List.of(Map.of("field", "priority", "toString", "High"), Map.of("field", "status", "toString", "Done"), "junk")));

        ExternalEvent event = adapter.parseWebhook(jira(), payload);

        assertEquals("ITSM-1", event.externalId());
        assertEquals("Done", event.externalStatus());
        assertEquals("acc-1", event.actor());
        assertNull(event.commentId());
        assertEquals("ITSM-1:jira:issue_updated:1700000000", event.eventId());

        payload.put("webhookEvent", "comment_created");
        payload.remove("changelog");
        payload.put("comment", Map.of("id", "10001", "body", "please look"));
        payload.put("user", Map.of("name", "bob"));
        ExternalEvent comment = adapter.parseWebhook(jira(), payload);
        assertNull(comment.externalStatus());
        assertEquals("10001", comment.commentId());
        assertEquals("please look", comment.commentText());
        assertEquals("bob", comment.actor());
        assertTrue(comment.eventId().endsWith(":10001"));

        payload.put("user", Map.of("key", "k1"));
        assertEquals("k1", adapter.parseWebhook(jira(), payload).actor());
        payload.put("user", Map.of("displayName", "Bob B"));
        assertEquals("Bob B", adapter.parseWebhook(jira(), payload).actor());
        payload.put("user", Map.of());
        assertNull(adapter.parseWebhook(jira(), payload).actor());
        payload.remove("user");
        payload.put("changelog", Map.of("items", "nope"));
        assertNull(adapter.parseWebhook(jira(), payload).externalStatus());
        payload.put("user", "not a map");
        assertNull(adapter.parseWebhook(jira(), payload).actor());
    }

    @Test
    @DisplayName("Jira: a payload that is not a Jira webhook is refused")
    void jiraWebhookInvalid() {
        JiraTicketingAdapter adapter = new JiraTicketingAdapter(new Wire().http);

        assertThrows(IllegalArgumentException.class, () -> adapter.parseWebhook(jira(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> adapter.parseWebhook(jira(), Map.of("issue", Map.of("key", "A-1"))));
        assertThrows(IllegalArgumentException.class, () -> adapter.parseWebhook(jira(), Map.of("webhookEvent", "x", "issue", Map.of())));
        assertThrows(IllegalArgumentException.class, () -> adapter.parseWebhook(jira(), Map.of("webhookEvent", "x", "issue", Map.of("key", " "))));
    }

    // ------------------------------------------------------------------ ServiceNow

    @Test
    @DisplayName("ServiceNow: a ticket is a row of the item's table, its id is table/sys_id, and the platform item is the correlation id")
    void snowCreate() {
        Wire wire = new Wire()
                .on("POST https://acme.service-now.com/api/now/table/incident", Map.of("result", Map.of("sys_id", "abc")))
                .on("POST https://acme.service-now.com/api/now/table/sc_request", Map.of("result", Map.of("sys_id", "def")))
                .on("POST https://acme.service-now.com/api/now/table/problem", Map.of("result", Map.of("sys_id", "ghi")));
        ServiceNowTicketingAdapter adapter = new ServiceNowTicketingAdapter(wire.http);
        assertEquals(Provider.SERVICENOW, adapter.provider());

        StepVerifier.create(adapter.createTicket(snow(), "a", draft(SubjectType.INCIDENT, "2")))
                .assertNext(ticket -> {
                    assertEquals("incident/abc", ticket.externalId());
                    assertEquals("https://acme.service-now.com/nav_to.do?uri=incident.do?sys_id=abc", ticket.url());
                }).verifyComplete();
        StepVerifier.create(adapter.createTicket(snow(), "a", draft(SubjectType.SERVICE_REQUEST, null))).assertNext(t -> assertEquals("sc_request/def", t.externalId())).verifyComplete();
        StepVerifier.create(adapter.createTicket(snow(), "a", new TicketDraft(SubjectType.PROBLEM, "t".repeat(300), "d", null, platformRef)))
                .assertNext(t -> assertEquals("problem/ghi", t.externalId())).verifyComplete();

        Map<String, Object> first = (Map<String, Object>) wire.bodies.get(0);
        assertEquals(platformRef.toString(), first.get("correlation_id"));
        assertEquals("2", first.get("state"));
        assertFalse(((Map<String, Object>) wire.bodies.get(1)).containsKey("state"));
        assertEquals(160, ((Map<String, Object>) wire.bodies.get(2)).get("short_description").toString().length());
    }

    @Test
    @DisplayName("ServiceNow: an answer without a sys_id is an error")
    void snowCreateWithoutSysId() {
        Wire wire = new Wire().on("POST https://acme.service-now.com/api/now/table/incident", Map.of("result", Map.of()));

        StepVerifier.create(new ServiceNowTicketingAdapter(wire.http).createTicket(snow(), "a", draft(SubjectType.INCIDENT, null))).expectError(ExternalTicketingException.class).verify();
    }

    @Test
    @DisplayName("ServiceNow: status and comments patch the row; ping reads one user")
    void snowUpdates() {
        Wire wire = new Wire()
                .on("PATCH https://acme.service-now.com/api/now/table/incident/abc", Map.of())
                .on("GET https://acme.service-now.com/api/now/table/sys_user?sysparm_limit=1", Map.of());
        ServiceNowTicketingAdapter adapter = new ServiceNowTicketingAdapter(wire.http);

        StepVerifier.create(adapter.updateStatus(snow(), "a", "incident/abc", "6")).verifyComplete();
        StepVerifier.create(adapter.addComment(snow(), "a", "incident/abc", "hello")).verifyComplete();
        StepVerifier.create(adapter.ping(snow(), "a")).verifyComplete();

        assertEquals(Map.of("state", "6"), wire.bodies.get(0));
        assertEquals(Map.of("comments", "hello"), wire.bodies.get(1));
    }

    @Test
    @DisplayName("ServiceNow: the Business Rule payload is read into one event, and an incomplete one is refused")
    void snowWebhook() {
        ServiceNowTicketingAdapter adapter = new ServiceNowTicketingAdapter(new Wire().http);

        ExternalEvent event = adapter.parseWebhook(snow(), Map.of("table", "incident", "sys_id", "abc", "event_id", "e-1", "state", 6, "comment", "done", "updated_by", "alice"));

        assertEquals("incident/abc", event.externalId());
        assertEquals("e-1", event.eventId());
        assertEquals("6", event.externalStatus());
        assertEquals("done", event.commentText());
        assertEquals("alice", event.actor());
        assertNull(event.commentId());

        ExternalEvent bare = adapter.parseWebhook(snow(), Map.of("table", "incident", "sys_id", "abc", "event_id", "e-2"));
        assertNull(bare.externalStatus());
        assertNull(bare.actor());

        assertThrows(IllegalArgumentException.class, () -> adapter.parseWebhook(snow(), Map.of("sys_id", "abc", "event_id", "e")));
        assertThrows(IllegalArgumentException.class, () -> adapter.parseWebhook(snow(), Map.of("table", "incident", "event_id", "e")));
        assertThrows(IllegalArgumentException.class, () -> adapter.parseWebhook(snow(), Map.of("table", "incident", "sys_id", "abc")));
    }
}
