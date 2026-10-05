package com.thinklab.infrastructure.adapter.out.integration.ticketing;

import com.thinklab.domain.exception.ExternalTicketingException;
import com.thinklab.domain.model.Connection;
import com.thinklab.domain.model.Connection.Provider;
import com.thinklab.domain.model.TicketLink.SubjectType;
import com.thinklab.domain.port.ExternalTicketingPort;
import io.micronaut.http.HttpMethod;
import jakarta.inject.Singleton;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

import static com.thinklab.infrastructure.adapter.out.integration.ticketing.JiraTicketingAdapter.map;
import static com.thinklab.infrastructure.adapter.out.integration.ticketing.JiraTicketingAdapter.text;
import static com.thinklab.infrastructure.adapter.out.integration.ticketing.JiraTicketingAdapter.truncate;

/**
 * ServiceNow adapter (Table API). A ticket is a row of {@code incident}, {@code sc_request} or {@code problem}, and its id here is
 * {@code <table>/<sys_id>} so that a later call knows the table (ServiceNow's {@code sys_id} alone does not say). The platform item is
 * written in {@code correlation_id}. The webhook is the one a ServiceNow Business Rule sends: {@code table}, {@code sys_id}, {@code state},
 * {@code comment}, {@code updated_by} and a unique {@code event_id}.
 */
@Singleton
public class ServiceNowTicketingAdapter implements ExternalTicketingPort {

    static final String NAME = "ServiceNow";
    private static final int MAX_SHORT_DESCRIPTION = 160;

    private final ProviderHttp http;

    public ServiceNowTicketingAdapter(ProviderHttp http) {
        this.http = http;
    }

    @Override
    public Provider provider() {
        return Provider.SERVICENOW;
    }

    @Override
    public Mono<ExternalTicket> createTicket(Connection connection, String authorization, TicketDraft draft) {
        String table = table(draft.type());
        Map<String, Object> body = new HashMap<>();
        body.put("short_description", truncate(draft.title(), MAX_SHORT_DESCRIPTION));
        body.put("description", draft.description());
        body.put("correlation_id", draft.platformRef().toString());
        if (draft.externalStatus() != null) {
            body.put("state", draft.externalStatus());
        }
        return http.call(NAME, "creating the record", HttpMethod.POST, base(connection) + "/api/now/table/" + table, authorization, body)
                .flatMap(created -> {
                    String sysId = text(map(created.get("result")).get("sys_id"));
                    return sysId == null
                            ? Mono.<ExternalTicket>error(new ExternalTicketingException("ServiceNow answered the creation without a sys_id."))
                            : Mono.just(new ExternalTicket(table + "/" + sysId, base(connection) + "/nav_to.do?uri=" + table + ".do?sys_id=" + sysId));
                });
    }

    @Override
    public Mono<Void> updateStatus(Connection connection, String authorization, String externalId, String externalStatus) {
        return http.call(NAME, "moving the record", HttpMethod.PATCH, record(connection, externalId), authorization, Map.of("state", externalStatus)).then();
    }

    @Override
    public Mono<Void> addComment(Connection connection, String authorization, String externalId, String text) {
        return http.call(NAME, "adding a comment", HttpMethod.PATCH, record(connection, externalId), authorization, Map.of("comments", text)).then();
    }

    @Override
    public Mono<Void> ping(Connection connection, String authorization) {
        return http.call(NAME, "checking the connection", HttpMethod.GET, base(connection) + "/api/now/table/sys_user?sysparm_limit=1", authorization, null).then();
    }

    @Override
    public ExternalEvent parseWebhook(Connection connection, Map<String, Object> payload) {
        String table = text(payload.get("table"));
        String sysId = text(payload.get("sys_id"));
        String eventId = text(payload.get("event_id"));
        if (table == null || sysId == null || eventId == null) {
            throw new IllegalArgumentException("Not a ServiceNow webhook payload: 'table', 'sys_id' and 'event_id' are required.");
        }
        return new ExternalEvent(eventId, table + "/" + sysId, text(payload.get("state")), null, text(payload.get("comment")), text(payload.get("updated_by")));
    }

    private static String record(Connection connection, String externalId) {
        return base(connection) + "/api/now/table/" + externalId;
    }

    private static String table(SubjectType type) {
        return switch (type) {
            case INCIDENT -> "incident";
            case SERVICE_REQUEST -> "sc_request";
            case PROBLEM -> "problem";
        };
    }

    private static String base(Connection connection) {
        return connection.getBaseUrl().replaceAll("/+$", "");
    }
}
