package com.thinklab.infrastructure.adapter.out.integration.ticketing;

import com.thinklab.domain.exception.ExternalTicketingException;
import com.thinklab.domain.model.Connection;
import com.thinklab.domain.model.Connection.Provider;
import com.thinklab.domain.model.TicketLink.SubjectType;
import com.thinklab.domain.port.ExternalTicketingPort;
import io.micronaut.http.HttpMethod;
import jakarta.inject.Singleton;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Jira adapter (REST API v2). A ticket is an issue of the connection's project; a status is not set but reached through the transition
 * that leads to it, found by the name of the status it ends in (so the connection's maps hold Jira status names). The webhook is Jira's
 * own: {@code webhookEvent}, {@code issue}, {@code changelog}, {@code comment}, {@code user}.
 */
@Singleton
public class JiraTicketingAdapter implements ExternalTicketingPort {

    static final String NAME = "Jira";
    private static final int MAX_SUMMARY = 250;

    private final ProviderHttp http;

    public JiraTicketingAdapter(ProviderHttp http) {
        this.http = http;
    }

    @Override
    public Provider provider() {
        return Provider.JIRA;
    }

    @Override
    public Mono<ExternalTicket> createTicket(Connection connection, String authorization, TicketDraft draft) {
        Map<String, Object> fields = Map.of(
                "project", Map.of("key", connection.getProjectKey()),
                "issuetype", Map.of("name", issueType(draft.type())),
                "summary", truncate(draft.title(), MAX_SUMMARY),
                "description", draft.description() + "\n\n(platform reference: " + draft.platformRef() + ")");
        return http.call(NAME, "creating the issue", HttpMethod.POST, base(connection) + "/rest/api/2/issue", authorization, Map.of("fields", fields))
                .flatMap(created -> {
                    Object key = created.get("key");
                    if (!(key instanceof String issueKey) || issueKey.isBlank()) {
                        return Mono.error(new ExternalTicketingException("Jira answered the creation without an issue key."));
                    }
                    ExternalTicket ticket = new ExternalTicket(issueKey, base(connection) + "/browse/" + issueKey);
                    return draft.externalStatus() == null ? Mono.just(ticket)
                            : updateStatus(connection, authorization, issueKey, draft.externalStatus()).thenReturn(ticket);
                });
    }

    @Override
    public Mono<Void> updateStatus(Connection connection, String authorization, String externalId, String externalStatus) {
        String issue = base(connection) + "/rest/api/2/issue/" + externalId;
        return http.call(NAME, "reading the issue status", HttpMethod.GET, issue + "?fields=status", authorization, null)
                .flatMap(current -> externalStatus.equalsIgnoreCase(statusName(current)) ? Mono.<Void>empty()
                        : http.call(NAME, "listing the transitions", HttpMethod.GET, issue + "/transitions", authorization, null)
                        .flatMap(transitions -> {
                            String id = transitionTo(transitions, externalStatus);
                            return id == null
                                    ? Mono.<Void>error(new ExternalTicketingException("Jira has no transition from the current status to '" + externalStatus + "'."))
                                    : http.call(NAME, "moving the issue", HttpMethod.POST, issue + "/transitions", authorization,
                                            Map.of("transition", Map.of("id", id))).then();
                        }));
    }

    @Override
    public Mono<Void> addComment(Connection connection, String authorization, String externalId, String text) {
        return http.call(NAME, "adding a comment", HttpMethod.POST, base(connection) + "/rest/api/2/issue/" + externalId + "/comment", authorization,
                Map.of("body", text)).then();
    }

    @Override
    public Mono<Void> ping(Connection connection, String authorization) {
        return http.call(NAME, "checking the connection", HttpMethod.GET, base(connection) + "/rest/api/2/myself", authorization, null).then();
    }

    @Override
    @SuppressWarnings("unchecked")
    public ExternalEvent parseWebhook(Connection connection, Map<String, Object> payload) {
        Map<String, Object> issue = map(payload.get("issue"));
        String key = text(issue.get("key"));
        String event = text(payload.get("webhookEvent"));
        if (key == null || event == null) {
            throw new IllegalArgumentException("Not a Jira webhook payload: 'issue.key' and 'webhookEvent' are required.");
        }
        Map<String, Object> comment = map(payload.get("comment"));
        String commentId = text(comment.get("id"));
        Map<String, Object> user = map(payload.get("user"));
        String actor = firstText(user.get("accountId"), user.get("name"), user.get("key"), user.get("displayName"));
        String eventId = key + ":" + event + ":" + text(payload.get("timestamp")) + (commentId != null ? ":" + commentId : "");
        String status = null;
        Object items = map(payload.get("changelog")).get("items");
        if (items instanceof List<?> list) {
            for (Object item : list) {
                Map<String, Object> change = map(item);
                if ("status".equals(change.get("field"))) {
                    status = text(change.get("toString"));
                }
            }
        }
        return new ExternalEvent(eventId, key, status, commentId, text(comment.get("body")), actor);
    }

    private static String transitionTo(Map<String, Object> transitions, String externalStatus) {
        Object list = transitions.get("transitions");
        if (list instanceof List<?> items) {
            for (Object item : items) {
                Map<String, Object> transition = map(item);
                if (externalStatus.equalsIgnoreCase(text(map(transition.get("to")).get("name")))) {
                    return text(transition.get("id"));
                }
            }
        }
        return null;
    }

    private static String statusName(Map<String, Object> issue) {
        return text(map(map(issue.get("fields")).get("status")).get("name"));
    }

    private static String issueType(SubjectType type) {
        return switch (type) {
            case INCIDENT -> "Incident";
            case SERVICE_REQUEST -> "Service Request";
            case PROBLEM -> "Problem";
        };
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    static String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    private static String firstText(Object... values) {
        for (Object value : values) {
            String text = text(value);
            if (text != null) {
                return text;
            }
        }
        return null;
    }

    static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String base(Connection connection) {
        return connection.getBaseUrl().replaceAll("/+$", "");
    }
}
