package com.thinklab.domain.model;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Core Domain Model representing the Connection Aggregate (BIAN Service Domain: {@code it-external-ticketing}): how this platform talks
 * to one ServiceNow or Jira instance of an organisation.
 *
 * <p><b>Credentials are never stored (ADR-032):</b> {@code secretRef} and {@code webhookSecretRef} are the <i>names</i> of environment
 * variables; the first holds the whole value of the {@code Authorization} header sent to the provider (for example {@code Basic ...} or
 * {@code Bearer ...}), the second the shared token the provider presents on an inbound webhook. The values are read when they are used and
 * never reach the database, the audit trail or a log.
 *
 * <p><b>Two small maps (ADR-031):</b> {@code outboundStatus} says which external status a platform status becomes (a platform status with
 * no entry is simply not pushed); {@code inboundActions} says which {@link InboundAction} an external status asks of the platform (an
 * external status with no entry is ignored). Both start from the provider defaults and are editable.
 *
 * <p>Strictly pure Java. Agnostic of frameworks, databases, or web layers.
 */
public class Connection {

    public static final int MAX_MAPPINGS = 30;
    private static final Pattern ENV_NAME = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Pattern JIRA_PROJECT = Pattern.compile("[A-Z][A-Z0-9_]{1,19}");

    private final UUID id;
    private final UUID organisationId;
    private String name;
    private final Provider provider;
    private String baseUrl;
    private String secretRef;
    private String webhookSecretRef;
    private String integrationActor;
    private String projectKey;
    private Map<String, String> outboundStatus;
    private Map<String, InboundAction> inboundActions;
    private ConnectionStatus status;
    private final Instant createdAt;
    private Instant updatedAt;
    private final List<ConnectionAuditEntry> auditTrail;

    private Connection(UUID id, UUID organisationId, String name, Provider provider, String baseUrl, String secretRef, String webhookSecretRef,
                       String integrationActor, String projectKey, Map<String, String> outbound, Map<String, InboundAction> inbound, String executor) {
        this.id = id;
        this.organisationId = organisationId;
        this.name = name;
        this.provider = provider;
        this.baseUrl = baseUrl;
        this.secretRef = secretRef;
        this.webhookSecretRef = webhookSecretRef;
        this.integrationActor = integrationActor;
        this.projectKey = projectKey;
        this.outboundStatus = new LinkedHashMap<>(outbound);
        this.inboundActions = new LinkedHashMap<>(inbound);
        this.status = ConnectionStatus.ACTIVE;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.auditTrail = new ArrayList<>();
        this.auditTrail.add(new ConnectionAuditEntry(this.createdAt, "INITIATED", executor, null, ConnectionStatus.ACTIVE, "Connection to " + provider + " configured."));
    }

    private Connection(UUID id, UUID organisationId, String name, Provider provider, String baseUrl, String secretRef, String webhookSecretRef,
                       String integrationActor, String projectKey, Map<String, String> outbound, Map<String, InboundAction> inbound,
                       ConnectionStatus status, Instant createdAt, Instant updatedAt, List<ConnectionAuditEntry> auditTrail) {
        this.id = id;
        this.organisationId = organisationId;
        this.name = name;
        this.provider = provider;
        this.baseUrl = baseUrl;
        this.secretRef = secretRef;
        this.webhookSecretRef = webhookSecretRef;
        this.integrationActor = integrationActor;
        this.projectKey = projectKey;
        this.outboundStatus = outbound != null ? new LinkedHashMap<>(outbound) : new LinkedHashMap<>();
        this.inboundActions = inbound != null ? new LinkedHashMap<>(inbound) : new LinkedHashMap<>();
        this.status = status != null ? status : ConnectionStatus.ACTIVE;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.updatedAt = updatedAt != null ? updatedAt : this.createdAt;
        this.auditTrail = auditTrail != null ? new ArrayList<>(auditTrail) : new ArrayList<>();
    }

    /** {@code outbound} or {@code inbound} left {@code null} start from the provider defaults. */
    public static Connection createNew(UUID id, UUID organisationId, String name, Provider provider, String baseUrl, String secretRef, String webhookSecretRef,
                                       String integrationActor, String projectKey, Map<String, String> outbound, Map<String, InboundAction> inbound, String executor) {
        if (id == null || organisationId == null) {
            throw new IllegalArgumentException("ID and Organisation ID are mandatory for Connection creation.");
        }
        if (provider == null) {
            throw new IllegalArgumentException("Provider is mandatory for a Connection.");
        }
        validate(name, provider, baseUrl, secretRef, webhookSecretRef, integrationActor, projectKey);
        Map<String, String> outboundMap = outbound != null ? checkedOutbound(outbound) : defaultOutbound(provider);
        Map<String, InboundAction> inboundMap = inbound != null ? checkedInbound(inbound) : defaultInbound(provider);
        requireExecutor(executor);
        return new Connection(id, organisationId, name, provider, baseUrl, secretRef, webhookSecretRef, integrationActor, normalisedProject(provider, projectKey),
                outboundMap, inboundMap, executor);
    }

    public static Connection reconstitute(UUID id, UUID organisationId, String name, Provider provider, String baseUrl, String secretRef, String webhookSecretRef,
                                          String integrationActor, String projectKey, Map<String, String> outbound, Map<String, InboundAction> inbound,
                                          ConnectionStatus status, Instant createdAt, Instant updatedAt, List<ConnectionAuditEntry> auditTrail) {
        if (id == null || organisationId == null || name == null || provider == null || baseUrl == null) {
            throw new IllegalArgumentException("ID, Organisation ID, Name, Provider and Base URL are mandatory to reconstitute a Connection.");
        }
        return new Connection(id, organisationId, name, provider, baseUrl, secretRef, webhookSecretRef, integrationActor, projectKey, outbound, inbound, status,
                createdAt, updatedAt, auditTrail);
    }

    // --- Domain Behaviors ---

    /** Behavior Qualifier: {@code update}. Everything but the provider and the organisation, in any status. */
    public ConnectionAuditEntry updateDetails(String newName, String newBaseUrl, String newSecretRef, String newWebhookSecretRef, String newIntegrationActor,
                                              String newProjectKey, Map<String, String> outbound, Map<String, InboundAction> inbound, String executor) {
        validate(newName, provider, newBaseUrl, newSecretRef, newWebhookSecretRef, newIntegrationActor, newProjectKey);
        Map<String, String> outboundMap = outbound != null ? checkedOutbound(outbound) : defaultOutbound(provider);
        Map<String, InboundAction> inboundMap = inbound != null ? checkedInbound(inbound) : defaultInbound(provider);
        this.name = newName;
        this.baseUrl = newBaseUrl;
        this.secretRef = newSecretRef;
        this.webhookSecretRef = newWebhookSecretRef;
        this.integrationActor = newIntegrationActor;
        this.projectKey = normalisedProject(provider, newProjectKey);
        this.outboundStatus = new LinkedHashMap<>(outboundMap);
        this.inboundActions = new LinkedHashMap<>(inboundMap);
        return record("UPDATED", executor, "Connection settings updated.");
    }

    /** Behavior Qualifier: {@code control/disable}. ACTIVE -&gt; DISABLED: nothing is sent and no webhook is accepted. */
    public ConnectionAuditEntry disable(String executor) {
        requireStatus(ConnectionStatus.ACTIVE);
        return transition(ConnectionStatus.DISABLED, "DISABLED", executor, "Disabled: nothing is sent and no webhook is accepted.");
    }

    /** Behavior Qualifier: {@code control/enable}. DISABLED -&gt; ACTIVE. */
    public ConnectionAuditEntry enable(String executor) {
        requireStatus(ConnectionStatus.DISABLED);
        return transition(ConnectionStatus.ACTIVE, "ENABLED", executor, "Enabled.");
    }

    /** A connection can only be used while it is ACTIVE. */
    public void requireActive() {
        if (status != ConnectionStatus.ACTIVE) {
            throw new com.thinklab.domain.exception.InvalidConnectionStatusException(String.format("Connection [%s] is %s: it can only be used while ACTIVE.", name, status));
        }
    }

    // --- Defaults ---

    /** What a platform status becomes in the provider by default (every one of them can be changed). */
    public static Map<String, String> defaultOutbound(Provider provider) {
        Map<String, String> map = new LinkedHashMap<>();
        if (provider == Provider.JIRA) {
            for (String open : List.of("NEW", "SUBMITTED", "ACKNOWLEDGED", "APPROVED")) {
                map.put(open, "To Do");
            }
            for (String working : List.of("UNDER_INVESTIGATION", "KNOWN_ERROR", "IN_PROGRESS", "IN_FULFILMENT")) {
                map.put(working, "In Progress");
            }
            for (String done : List.of("RESOLVED", "FULFILLED", "CLOSED")) {
                map.put(done, "Done");
            }
            map.put("CANCELLED", "Cancelled");
        } else {
            for (String open : List.of("NEW", "SUBMITTED", "APPROVED")) {
                map.put(open, "1");
            }
            for (String working : List.of("ACKNOWLEDGED", "UNDER_INVESTIGATION", "KNOWN_ERROR", "IN_PROGRESS", "IN_FULFILMENT")) {
                map.put(working, "2");
            }
            map.put("ON_HOLD", "3");
            for (String done : List.of("RESOLVED", "FULFILLED")) {
                map.put(done, "6");
            }
            map.put("CLOSED", "7");
            map.put("CANCELLED", "8");
        }
        return map;
    }

    /** What an external status asks of the platform by default; anything else is ignored. */
    public static Map<String, InboundAction> defaultInbound(Provider provider) {
        Map<String, InboundAction> map = new LinkedHashMap<>();
        if (provider == Provider.JIRA) {
            map.put("In Progress", InboundAction.START);
            map.put("Done", InboundAction.RESOLVE);
            map.put("Cancelled", InboundAction.CANCEL);
        } else {
            map.put("2", InboundAction.START);
            map.put("6", InboundAction.RESOLVE);
            map.put("7", InboundAction.CLOSE);
            map.put("8", InboundAction.CANCEL);
        }
        return map;
    }

    // --- Internal helpers ---

    private ConnectionAuditEntry transition(ConnectionStatus newStatus, String action, String executor, String detail) {
        requireExecutor(executor);
        ConnectionStatus previous = this.status;
        this.status = newStatus;
        this.updatedAt = Instant.now();
        ConnectionAuditEntry entry = new ConnectionAuditEntry(this.updatedAt, action, executor, previous, newStatus, detail);
        this.auditTrail.add(entry);
        return entry;
    }

    private ConnectionAuditEntry record(String action, String executor, String detail) {
        requireExecutor(executor);
        this.updatedAt = Instant.now();
        ConnectionAuditEntry entry = new ConnectionAuditEntry(this.updatedAt, action, executor, this.status, this.status, detail);
        this.auditTrail.add(entry);
        return entry;
    }

    private void requireStatus(ConnectionStatus expected) {
        if (this.status != expected) {
            throw new com.thinklab.domain.exception.InvalidConnectionStatusException(String.format(
                    "Illegal transition: Connection is [%s], expected [%s].", this.status, expected));
        }
    }

    private static void validate(String name, Provider provider, String baseUrl, String secretRef, String webhookSecretRef, String integrationActor, String projectKey) {
        if (name == null || name.isBlank() || name.length() > 80) {
            throw new IllegalArgumentException("Name is mandatory for a Connection (up to 80 characters).");
        }
        validateBaseUrl(baseUrl);
        if (secretRef == null || !ENV_NAME.matcher(secretRef).matches()) {
            throw new IllegalArgumentException("The secret reference is the NAME of an environment variable: capital letters, digits and underscores.");
        }
        if (webhookSecretRef == null || !ENV_NAME.matcher(webhookSecretRef).matches()) {
            throw new IllegalArgumentException("The webhook secret reference is the NAME of an environment variable: capital letters, digits and underscores.");
        }
        if (secretRef.equals(webhookSecretRef)) {
            throw new IllegalArgumentException("The credentials and the webhook token must come from two different variables.");
        }
        if (integrationActor == null || integrationActor.isBlank() || integrationActor.length() > 200) {
            throw new IllegalArgumentException("The integration actor (the account the credentials act as) is mandatory, so events caused by this integration can be ignored.");
        }
        if (provider == Provider.JIRA && (projectKey == null || !JIRA_PROJECT.matcher(projectKey).matches())) {
            throw new IllegalArgumentException("A Jira connection needs the project key, such as ITSM.");
        }
    }

    /** An http(s) URL of a host, without credentials, query or fragment: the policy on https and on which hosts is the application layer's. */
    private static void validateBaseUrl(String baseUrl) {
        URI uri;
        try {
            uri = URI.create(baseUrl == null ? "" : baseUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("The base URL is not a valid URL.");
        }
        boolean web = "https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme());
        if (!web || uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("The base URL is an http(s) address of a host, without credentials, query or fragment.");
        }
    }

    private static String normalisedProject(Provider provider, String projectKey) {
        return provider == Provider.JIRA ? projectKey : null;
    }

    private static Map<String, String> checkedOutbound(Map<String, String> given) {
        if (given.size() > MAX_MAPPINGS) {
            throw new IllegalArgumentException("At most " + MAX_MAPPINGS + " status mappings.");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : given.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null || entry.getValue().isBlank() || entry.getValue().length() > 60) {
                throw new IllegalArgumentException("A status mapping needs a platform status and an external status of up to 60 characters.");
            }
            result.put(entry.getKey().trim().toUpperCase(java.util.Locale.ROOT), entry.getValue().trim());
        }
        return result;
    }

    private static Map<String, InboundAction> checkedInbound(Map<String, InboundAction> given) {
        if (given.size() > MAX_MAPPINGS) {
            throw new IllegalArgumentException("At most " + MAX_MAPPINGS + " status mappings.");
        }
        Map<String, InboundAction> result = new LinkedHashMap<>();
        for (Map.Entry<String, InboundAction> entry : given.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getKey().length() > 60 || entry.getValue() == null) {
                throw new IllegalArgumentException("A status mapping needs an external status of up to 60 characters and an action.");
            }
            result.put(entry.getKey().trim(), entry.getValue());
        }
        return result;
    }

    private static void requireExecutor(String executor) {
        if (executor == null || executor.isBlank()) {
            throw new IllegalArgumentException("Executor is mandatory for auditable Connection mutations.");
        }
    }

    // --- Getters ---

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public String getName() { return name; }
    public Provider getProvider() { return provider; }
    public String getBaseUrl() { return baseUrl; }
    public String getSecretRef() { return secretRef; }
    public String getWebhookSecretRef() { return webhookSecretRef; }
    public String getIntegrationActor() { return integrationActor; }
    public String getProjectKey() { return projectKey; }
    public Map<String, String> getOutboundStatus() { return Collections.unmodifiableMap(outboundStatus); }
    public Map<String, InboundAction> getInboundActions() { return Collections.unmodifiableMap(inboundActions); }
    public ConnectionStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<ConnectionAuditEntry> getAuditTrail() { return Collections.unmodifiableList(auditTrail); }

    // --- Nested Value Objects ---

    public enum Provider { JIRA, SERVICENOW }

    public enum ConnectionStatus { ACTIVE, DISABLED }

    /** What an external status can ask of the platform. Each maps onto the control route of the item (ADR-031); one the item cannot take is a recorded conflict, never forced. */
    public enum InboundAction {
        ACKNOWLEDGE, START, RESUME, RESOLVE, CLOSE, CANCEL;

        /** An incident takes them all; a request can be started, resolved (fulfilled), closed and cancelled; a problem can be started (investigated), resolved, closed and cancelled. */
        public boolean supportedBy(TicketLink.SubjectType type) {
            return switch (this) {
                case ACKNOWLEDGE, RESUME -> type == TicketLink.SubjectType.INCIDENT;
                case START, RESOLVE, CLOSE, CANCEL -> true;
            };
        }
    }

    /** Immutable forensic ledger entry, mirroring the platform's established audit-trail pattern. */
    public record ConnectionAuditEntry(Instant occurredAt, String action, String executor, ConnectionStatus fromStatus, ConnectionStatus toStatus, String detail) {}
}
