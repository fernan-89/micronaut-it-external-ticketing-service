package com.thinklab.domain.model;

import com.thinklab.domain.exception.InvalidConnectionStatusException;
import com.thinklab.domain.model.Connection.ConnectionAuditEntry;
import com.thinklab.domain.model.Connection.ConnectionStatus;
import com.thinklab.domain.model.Connection.InboundAction;
import com.thinklab.domain.model.Connection.Provider;
import com.thinklab.domain.model.TicketLink.SubjectType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionTest {

    private static final String BASE = "https://acme.atlassian.net";
    private final UUID org = UUID.randomUUID();

    private Connection jira() {
        return Connection.createNew(UUID.randomUUID(), org, "Jira prod", Provider.JIRA, BASE, "JIRA_AUTH", "JIRA_HOOK", "svc-thinklab", "ITSM", null, null, "op-1");
    }

    private Connection servicenow() {
        return Connection.createNew(UUID.randomUUID(), org, "SNOW prod", Provider.SERVICENOW, "https://acme.service-now.com/", "SNOW_AUTH", "SNOW_HOOK", "svc-thinklab", null, null, null, "op-1");
    }

    private static void rejects(Runnable action) {
        assertThrows(IllegalArgumentException.class, action::run);
    }

    @Test
    @DisplayName("a new Jira connection starts ACTIVE with the provider defaults, its project key and an INITIATED entry")
    void createJira() {
        Connection connection = jira();

        assertEquals(ConnectionStatus.ACTIVE, connection.getStatus());
        assertEquals("ITSM", connection.getProjectKey());
        assertEquals("In Progress", connection.getOutboundStatus().get("UNDER_INVESTIGATION"));
        assertEquals(InboundAction.RESOLVE, connection.getInboundActions().get("Done"));
        assertEquals("INITIATED", connection.getAuditTrail().get(0).action());
        assertEquals("svc-thinklab", connection.getIntegrationActor());
        assertEquals("JIRA_AUTH", connection.getSecretRef());
        assertEquals("JIRA_HOOK", connection.getWebhookSecretRef());
        assertEquals(connection.getCreatedAt(), connection.getUpdatedAt());
    }

    @Test
    @DisplayName("a ServiceNow connection has no project key, even if one is given, and uses the state codes")
    void createServiceNow() {
        Connection connection = Connection.createNew(UUID.randomUUID(), org, "SNOW", Provider.SERVICENOW, "http://snow.test:9000", "SNOW_AUTH", "SNOW_HOOK", "svc", "ITSM",
                null, null, "op-1");

        assertNull(connection.getProjectKey());
        assertEquals("6", connection.getOutboundStatus().get("RESOLVED"));
        assertEquals("3", connection.getOutboundStatus().get("ON_HOLD"));
        assertEquals(InboundAction.CLOSE, connection.getInboundActions().get("7"));
        assertEquals(Provider.SERVICENOW, connection.getProvider());
    }

    @Test
    @DisplayName("given maps are normalised: outbound platform statuses upper-cased, values trimmed")
    void givenMaps() {
        Connection connection = Connection.createNew(UUID.randomUUID(), org, "Jira", Provider.JIRA, BASE, "JIRA_AUTH", "JIRA_HOOK", "svc", "ITSM",
                Map.of(" new ", " Backlog "), Map.of(" Done ", InboundAction.CLOSE), "op-1");

        assertEquals(Map.of("NEW", "Backlog"), connection.getOutboundStatus());
        assertEquals(Map.of("Done", InboundAction.CLOSE), connection.getInboundActions());
    }

    @Test
    @DisplayName("creation guards: ids, provider, name, URL, secret names, actor, project key and executor")
    void creationGuards() {
        UUID id = UUID.randomUUID();
        rejects(() -> Connection.createNew(null, org, "n", Provider.JIRA, BASE, "A_B", "C_D", "svc", "ITSM", null, null, "op"));
        rejects(() -> Connection.createNew(id, null, "n", Provider.JIRA, BASE, "A_B", "C_D", "svc", "ITSM", null, null, "op"));
        rejects(() -> Connection.createNew(id, org, "n", null, BASE, "A_B", "C_D", "svc", "ITSM", null, null, "op"));
        rejects(() -> Connection.createNew(id, org, null, Provider.JIRA, BASE, "A_B", "C_D", "svc", "ITSM", null, null, "op"));
        rejects(() -> Connection.createNew(id, org, " ", Provider.JIRA, BASE, "A_B", "C_D", "svc", "ITSM", null, null, "op"));
        rejects(() -> Connection.createNew(id, org, "x".repeat(81), Provider.JIRA, BASE, "A_B", "C_D", "svc", "ITSM", null, null, "op"));
        rejects(() -> Connection.createNew(id, org, "n", Provider.JIRA, null, "A_B", "C_D", "svc", "ITSM", null, null, "op"));
        rejects(() -> Connection.createNew(id, org, "n", Provider.JIRA, "not a url", "A_B", "C_D", "svc", "ITSM", null, null, "op"));
        rejects(() -> Connection.createNew(id, org, "n", Provider.JIRA, "ftp://host", "A_B", "C_D", "svc", "ITSM", null, null, "op"));
        rejects(() -> Connection.createNew(id, org, "n", Provider.JIRA, "https://user:pw@host", "A_B", "C_D", "svc", "ITSM", null, null, "op"));
        rejects(() -> Connection.createNew(id, org, "n", Provider.JIRA, "https://host?x=1", "A_B", "C_D", "svc", "ITSM", null, null, "op"));
        rejects(() -> Connection.createNew(id, org, "n", Provider.JIRA, "https://host#frag", "A_B", "C_D", "svc", "ITSM", null, null, "op"));
        rejects(() -> Connection.createNew(id, org, "n", Provider.JIRA, "/relative", "A_B", "C_D", "svc", "ITSM", null, null, "op"));
        rejects(() -> Connection.createNew(id, org, "n", Provider.JIRA, "https:///no-host", "A_B", "C_D", "svc", "ITSM", null, null, "op"));
        rejects(() -> Connection.createNew(id, org, "n", Provider.JIRA, BASE, null, "C_D", "svc", "ITSM", null, null, "op"));
        rejects(() -> Connection.createNew(id, org, "n", Provider.JIRA, BASE, "lower", "C_D", "svc", "ITSM", null, null, "op"));
        rejects(() -> Connection.createNew(id, org, "n", Provider.JIRA, BASE, "A_B", null, "svc", "ITSM", null, null, "op"));
        rejects(() -> Connection.createNew(id, org, "n", Provider.JIRA, BASE, "A_B", "has space", "svc", "ITSM", null, null, "op"));
        rejects(() -> Connection.createNew(id, org, "n", Provider.JIRA, BASE, "SAME", "SAME", "svc", "ITSM", null, null, "op"));
        rejects(() -> Connection.createNew(id, org, "n", Provider.JIRA, BASE, "A_B", "C_D", null, "ITSM", null, null, "op"));
        rejects(() -> Connection.createNew(id, org, "n", Provider.JIRA, BASE, "A_B", "C_D", " ", "ITSM", null, null, "op"));
        rejects(() -> Connection.createNew(id, org, "n", Provider.JIRA, BASE, "A_B", "C_D", "x".repeat(201), "ITSM", null, null, "op"));
        rejects(() -> Connection.createNew(id, org, "n", Provider.JIRA, BASE, "A_B", "C_D", "svc", null, null, null, "op"));
        rejects(() -> Connection.createNew(id, org, "n", Provider.JIRA, BASE, "A_B", "C_D", "svc", "itsm", null, null, "op"));
        rejects(() -> Connection.createNew(id, org, "n", Provider.JIRA, BASE, "A_B", "C_D", "svc", "ITSM", null, null, null));
        rejects(() -> Connection.createNew(id, org, "n", Provider.JIRA, BASE, "A_B", "C_D", "svc", "ITSM", null, null, " "));
    }

    @Test
    @DisplayName("mapping guards: too many, blank key, blank/long value, null action")
    void mappingGuards() {
        UUID id = UUID.randomUUID();
        Map<String, String> tooManyOut = new HashMap<>();
        Map<String, InboundAction> tooManyIn = new HashMap<>();
        for (int i = 0; i <= Connection.MAX_MAPPINGS; i++) {
            tooManyOut.put("S" + i, "v");
            tooManyIn.put("S" + i, InboundAction.START);
        }
        Map<String, String> nullKey = new HashMap<>();
        nullKey.put(null, "v");
        Map<String, String> nullValue = new HashMap<>();
        nullValue.put("A", null);
        Map<String, InboundAction> nullInboundKey = new HashMap<>();
        nullInboundKey.put(null, InboundAction.START);
        Map<String, InboundAction> nullAction = new HashMap<>();
        nullAction.put("Done", null);

        rejects(() -> create(id, tooManyOut, null));
        rejects(() -> create(id, nullKey, null));
        rejects(() -> create(id, Map.of(" ", "v"), null));
        rejects(() -> create(id, nullValue, null));
        rejects(() -> create(id, Map.of("A", " "), null));
        rejects(() -> create(id, Map.of("A", "v".repeat(61)), null));
        rejects(() -> create(id, null, tooManyIn));
        rejects(() -> create(id, null, nullInboundKey));
        rejects(() -> create(id, null, Map.of(" ", InboundAction.START)));
        rejects(() -> create(id, null, Map.of("k".repeat(61), InboundAction.START)));
        rejects(() -> create(id, null, nullAction));
    }

    private Connection create(UUID id, Map<String, String> outbound, Map<String, InboundAction> inbound) {
        return Connection.createNew(id, org, "n", Provider.JIRA, BASE, "A_B", "C_D", "svc", "ITSM", outbound, inbound, "op");
    }

    @Test
    @DisplayName("update changes everything but the provider; maps left null go back to the defaults; ServiceNow drops the project key")
    void update() {
        Connection connection = jira();

        ConnectionAuditEntry entry = connection.updateDetails("Jira 2", "https://other.atlassian.net", "NEW_AUTH", "NEW_HOOK", "svc2", "OPS",
                Map.of("NEW", "Open"), Map.of("Closed", InboundAction.CLOSE), "op-2");

        assertEquals("UPDATED", entry.action());
        assertEquals(ConnectionStatus.ACTIVE, entry.fromStatus());
        assertEquals("Jira 2", connection.getName());
        assertEquals("OPS", connection.getProjectKey());
        assertEquals(Map.of("NEW", "Open"), connection.getOutboundStatus());
        assertEquals(Provider.JIRA, connection.getProvider());

        connection.updateDetails("Jira 2", BASE, "NEW_AUTH", "NEW_HOOK", "svc2", "OPS", null, null, "op-2");
        assertEquals("To Do", connection.getOutboundStatus().get("NEW"));
        assertEquals(InboundAction.START, connection.getInboundActions().get("In Progress"));

        Connection snow = servicenow();
        snow.updateDetails("SNOW", "https://acme.service-now.com", "SNOW_AUTH", "SNOW_HOOK", "svc", "IGNORED", null, null, "op-2");
        assertNull(snow.getProjectKey());

        rejects(() -> connection.updateDetails("", BASE, "A_B", "C_D", "svc", "ITSM", null, null, "op"));
        rejects(() -> connection.updateDetails("n", BASE, "A_B", "C_D", "svc", "ITSM", null, null, " "));
    }

    @Test
    @DisplayName("disable then enable follow the FSM, each with its audit entry; anything else is a 409-style refusal")
    void lifecycle() {
        Connection connection = jira();

        assertThrows(InvalidConnectionStatusException.class, () -> connection.enable("op"));
        ConnectionAuditEntry disabled = connection.disable("op-2");
        assertEquals(ConnectionStatus.DISABLED, connection.getStatus());
        assertEquals(ConnectionStatus.ACTIVE, disabled.fromStatus());
        assertEquals(ConnectionStatus.DISABLED, disabled.toStatus());
        assertThrows(InvalidConnectionStatusException.class, () -> connection.disable("op"));
        assertThrows(InvalidConnectionStatusException.class, connection::requireActive);

        ConnectionAuditEntry enabled = connection.enable("op-3");
        assertEquals("ENABLED", enabled.action());
        connection.requireActive();
        assertEquals(3, connection.getAuditTrail().size());
        rejects(() -> connection.disable(null));
    }

    @Test
    @DisplayName("each action says which items can take it")
    void inboundActionSupport() {
        for (InboundAction action : List.of(InboundAction.START, InboundAction.RESOLVE, InboundAction.CLOSE, InboundAction.CANCEL)) {
            for (SubjectType type : SubjectType.values()) {
                assertTrue(action.supportedBy(type));
            }
        }
        for (InboundAction action : List.of(InboundAction.ACKNOWLEDGE, InboundAction.RESUME)) {
            assertTrue(action.supportedBy(SubjectType.INCIDENT));
            assertFalse(action.supportedBy(SubjectType.SERVICE_REQUEST));
            assertFalse(action.supportedBy(SubjectType.PROBLEM));
        }
    }

    @Test
    @DisplayName("reconstitute keeps what was stored, defaults what is missing, and refuses a missing identity")
    void reconstitute() {
        UUID id = UUID.randomUUID();
        Instant created = Instant.parse("2026-01-01T00:00:00Z");
        Connection full = Connection.reconstitute(id, org, "n", Provider.JIRA, BASE, "A_B", "C_D", "svc", "ITSM", Map.of("NEW", "x"), Map.of("y", InboundAction.START),
                ConnectionStatus.DISABLED, created, created, List.of());
        assertEquals(ConnectionStatus.DISABLED, full.getStatus());
        assertEquals(created, full.getCreatedAt());
        assertEquals(Map.of("NEW", "x"), full.getOutboundStatus());

        Connection bare = Connection.reconstitute(id, org, "n", Provider.JIRA, BASE, null, null, null, null, null, null, null, null, null, null);
        assertEquals(ConnectionStatus.ACTIVE, bare.getStatus());
        assertTrue(bare.getOutboundStatus().isEmpty());
        assertTrue(bare.getInboundActions().isEmpty());
        assertTrue(bare.getAuditTrail().isEmpty());
        assertEquals(bare.getCreatedAt(), bare.getUpdatedAt());

        rejects(() -> Connection.reconstitute(null, org, "n", Provider.JIRA, BASE, null, null, null, null, null, null, null, null, null, null));
        rejects(() -> Connection.reconstitute(id, null, "n", Provider.JIRA, BASE, null, null, null, null, null, null, null, null, null, null));
        rejects(() -> Connection.reconstitute(id, org, null, Provider.JIRA, BASE, null, null, null, null, null, null, null, null, null, null));
        rejects(() -> Connection.reconstitute(id, org, "n", null, BASE, null, null, null, null, null, null, null, null, null, null));
        rejects(() -> Connection.reconstitute(id, org, "n", Provider.JIRA, null, null, null, null, null, null, null, null, null, null, null));
    }
}
