package com.thinklab.application.usecase;

import com.thinklab.application.config.ExternalTicketingProperties;
import com.thinklab.application.dto.request.InitiateConnectionRequest;
import com.thinklab.application.dto.request.UpdateConnectionRequest;
import com.thinklab.application.mapper.ConnectionMapper;
import com.thinklab.domain.exception.ConnectionNotFoundException;
import com.thinklab.domain.exception.DuplicateConnectionException;
import com.thinklab.domain.exception.ExternalTicketingException;
import com.thinklab.domain.exception.IntegrationAccessDeniedException;
import com.thinklab.domain.model.Connection;
import com.thinklab.domain.model.Connection.ConnectionStatus;
import com.thinklab.domain.model.Connection.Provider;
import com.thinklab.domain.port.ExternalTicketingPort;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.port.SecretResolverPort;
import com.thinklab.domain.repository.ConnectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConnectionUseCaseTest {

    private static final String BASE = "https://acme.atlassian.net";

    @Mock private ConnectionRepository repository;
    @Mock private HashServicePort hashService;
    @Mock private SecretResolverPort secrets;
    @Mock private ExternalTicketingPort jiraPort;

    private final UUID org = UUID.randomUUID();
    private final ExternalTicketingProperties properties = new ExternalTicketingProperties();
    private BaseUrlPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new BaseUrlPolicy(properties);
    }

    private Connection connection() {
        return Connection.createNew(UUID.randomUUID(), org, "Jira prod", Provider.JIRA, BASE, "JIRA_AUTH", "JIRA_HOOK", "svc", "ITSM", null, null, "op-1");
    }

    private InitiateConnectionRequest initiateRequest(String url) {
        return new InitiateConnectionRequest("Jira prod", Provider.JIRA, url, "JIRA_AUTH", "JIRA_HOOK", "svc", "ITSM", null, null);
    }

    private UpdateConnectionRequest updateRequest(String name) {
        return new UpdateConnectionRequest(name, " " + BASE + " ", "JIRA_AUTH", "JIRA_HOOK", "svc", "ITSM", null, null);
    }

    // ------------------------------------------------------------------ BaseUrlPolicy

    @Test
    @DisplayName("the policy demands https and refuses loopback, link-local and private address literals")
    void policy() {
        policy.check("https://acme.atlassian.net");
        policy.check("https://8.8.8.8");
        policy.check("https://172.32.0.1");
        policy.check("https://172.15.0.1");
        policy.check("https://169.253.0.1");
        policy.check("https://192.169.0.1");
        policy.check("https://999.999.999.999.com");

        for (String refused : List.of("http://acme.atlassian.net", "https://localhost", "https://[::1]", "https://[fe80::1]", "https://[fc00::1]", "https://[fd00::1]",
                "https://10.1.2.3", "https://127.0.0.1", "https://0.0.0.0", "https://169.254.169.254", "https://172.16.0.1", "https://172.31.255.1",
                "https://192.168.1.1", "https://2130706433")) {
            assertThrows(IllegalArgumentException.class, () -> policy.check(refused), refused);
        }
    }

    @Test
    @DisplayName("a host on the insecure list (a test double) may use http and a private address")
    void insecureHosts() {
        properties.setInsecureHosts(List.of("LOCALHOST", "mock.test"));

        policy.check("http://localhost:9000");
        policy.check(" http://mock.test ");
        assertThrows(IllegalArgumentException.class, () -> policy.check("http://127.0.0.1:9000"));
        assertTrue(properties.getInsecureHosts().contains("mock.test"));
    }

    @Test
    @DisplayName("IntegrationAccess is a utility class")
    void utilityClass() throws Exception {
        Constructor<IntegrationAccess> constructor = IntegrationAccess.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        InvocationTargetException error = assertThrows(InvocationTargetException.class, constructor::newInstance);
        assertTrue(error.getCause() instanceof UnsupportedOperationException);
        assertThrows(IntegrationAccessDeniedException.class, () -> IntegrationAccess.requireStaff("REQUESTER", "do it"));
        IntegrationAccess.requireStaff(null, "do it");
        IntegrationAccess.requireStaff("AGENT", "do it");
    }

    // ------------------------------------------------------------------ Initiate

    @Test
    @DisplayName("initiate checks the URL, the name, saves the connection and reports which secrets are set (never their values)")
    void initiate() {
        when(repository.existsByName("Jira prod", org)).thenReturn(Mono.just(false));
        when(hashService.generateSovereignId("connection-creation")).thenReturn(Mono.just(UUID.randomUUID()));
        when(repository.create(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));
        when(secrets.resolve("JIRA_AUTH")).thenReturn("Basic abc");
        when(secrets.resolve("JIRA_HOOK")).thenReturn(null);

        StepVerifier.create(new InitiateConnectionUseCase(hashService, repository, policy, secrets).execute(org, initiateRequest(" " + BASE + " "), "op-1", "AGENT"))
                .assertNext(response -> {
                    assertEquals("JIRA", response.provider());
                    assertEquals(BASE, response.baseUrl());
                    assertEquals("JIRA_AUTH", response.secretRef());
                    assertTrue(response.secretConfigured());
                    assertFalse(response.webhookSecretConfigured());
                    assertEquals("ACTIVE", response.status());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("initiate refuses a requester, an unsafe URL and a name already used")
    void initiateRefusals() {
        InitiateConnectionUseCase useCase = new InitiateConnectionUseCase(hashService, repository, policy, secrets);

        StepVerifier.create(useCase.execute(org, initiateRequest(BASE), "op", "REQUESTER")).expectError(IntegrationAccessDeniedException.class).verify();
        StepVerifier.create(useCase.execute(org, initiateRequest("http://acme.atlassian.net"), "op", null)).expectError(IllegalArgumentException.class).verify();

        when(repository.existsByName("Jira prod", org)).thenReturn(Mono.just(true));
        StepVerifier.create(useCase.execute(org, initiateRequest(BASE), "op", null)).expectError(DuplicateConnectionException.class).verify();
        verify(hashService, never()).generateSovereignId(any());
    }

    // ------------------------------------------------------------------ Update

    @Test
    @DisplayName("update keeps the same name without asking the repository, trims the URL and saves with the status loaded")
    void update() {
        Connection connection = connection();
        when(repository.findById(connection.getId(), org)).thenReturn(Mono.just(connection));
        when(repository.save(any(), any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(new UpdateConnectionUseCase(repository, policy).execute(connection.getId(), org, updateRequest("Jira prod"), "op-2", "AGENT")).verifyComplete();

        verify(repository, never()).existsByName(any(), any());
        verify(repository).save(eq(connection), eq(ConnectionStatus.ACTIVE), any());
        assertEquals(BASE, connection.getBaseUrl());
    }

    @Test
    @DisplayName("update to a new name checks it is free, and refuses one that is taken")
    void updateRename() {
        Connection connection = connection();
        when(repository.findById(connection.getId(), org)).thenReturn(Mono.just(connection));
        when(repository.existsByName("Taken", org)).thenReturn(Mono.just(true));
        when(repository.existsByName("Free", org)).thenReturn(Mono.just(false));
        when(repository.save(any(), any(), any())).thenReturn(Mono.empty());
        UpdateConnectionUseCase useCase = new UpdateConnectionUseCase(repository, policy);

        StepVerifier.create(useCase.execute(connection.getId(), org, updateRequest("Taken"), "op", "AGENT")).expectError(DuplicateConnectionException.class).verify();
        StepVerifier.create(useCase.execute(connection.getId(), org, updateRequest("Free"), "op", "AGENT")).verifyComplete();
        assertEquals("Free", connection.getName());
    }

    @Test
    @DisplayName("update refuses a requester, an unsafe URL and an unknown connection")
    void updateRefusals() {
        UpdateConnectionUseCase useCase = new UpdateConnectionUseCase(repository, policy);
        UUID id = UUID.randomUUID();

        StepVerifier.create(useCase.execute(id, org, updateRequest("n"), "op", "REQUESTER")).expectError(IntegrationAccessDeniedException.class).verify();
        StepVerifier.create(useCase.execute(id, org, new UpdateConnectionRequest("n", "http://acme.atlassian.net", "A_B", "C_D", "svc", "ITSM", null, null), "op", null))
                .expectError(IllegalArgumentException.class).verify();
        when(repository.findById(id, org)).thenReturn(Mono.empty());
        StepVerifier.create(useCase.execute(id, org, updateRequest("n"), "op", null)).expectError(ConnectionNotFoundException.class).verify();
    }

    // ------------------------------------------------------------------ Control, retrieve, audit

    @Test
    @DisplayName("control disables and enables through the workflow; an unknown connection and a requester are refused")
    void control() {
        Connection connection = connection();
        when(repository.findById(connection.getId(), org)).thenReturn(Mono.just(connection));
        when(repository.save(any(), any(), any())).thenReturn(Mono.empty());
        ControlConnectionUseCase useCase = new ControlConnectionUseCase(new ConnectionWorkflow(repository));

        StepVerifier.create(useCase.execute(connection.getId(), org, ControlConnectionUseCase.Action.DISABLE, "op", "AGENT")).verifyComplete();
        assertEquals(ConnectionStatus.DISABLED, connection.getStatus());
        StepVerifier.create(useCase.execute(connection.getId(), org, ControlConnectionUseCase.Action.ENABLE, "op", "AGENT")).verifyComplete();
        assertEquals(ConnectionStatus.ACTIVE, connection.getStatus());
        verify(repository).save(eq(connection), eq(ConnectionStatus.ACTIVE), any());
        verify(repository).save(eq(connection), eq(ConnectionStatus.DISABLED), any());

        StepVerifier.create(useCase.execute(connection.getId(), org, ControlConnectionUseCase.Action.DISABLE, "op", "REQUESTER")).expectError(IntegrationAccessDeniedException.class).verify();
        UUID missing = UUID.randomUUID();
        when(repository.findById(missing, org)).thenReturn(Mono.empty());
        StepVerifier.create(useCase.execute(missing, org, ControlConnectionUseCase.Action.DISABLE, "op", null)).expectError(ConnectionNotFoundException.class).verify();
    }

    @Test
    @DisplayName("retrieve and list report the secrets as configured or not, never their values; a requester is refused")
    void retrieve() {
        Connection connection = connection();
        when(repository.findById(connection.getId(), org)).thenReturn(Mono.just(connection));
        when(repository.findAll(org)).thenReturn(Flux.just(connection));
        when(secrets.resolve("JIRA_AUTH")).thenReturn("Basic abc");
        when(secrets.resolve("JIRA_HOOK")).thenReturn("tok");

        StepVerifier.create(new RetrieveConnectionUseCase(repository, secrets).execute(connection.getId(), org, "AGENT"))
                .assertNext(response -> assertTrue(response.secretConfigured() && response.webhookSecretConfigured())).verifyComplete();
        StepVerifier.create(new RetrieveConnectionsUseCase(repository, secrets).execute(org, "AGENT"))
                .assertNext(response -> assertEquals(connection.getId(), response.id())).verifyComplete();

        StepVerifier.create(new RetrieveConnectionUseCase(repository, secrets).execute(connection.getId(), org, "REQUESTER")).expectError(IntegrationAccessDeniedException.class).verify();
        StepVerifier.create(new RetrieveConnectionsUseCase(repository, secrets).execute(org, "REQUESTER")).expectError(IntegrationAccessDeniedException.class).verify();
        UUID missing = UUID.randomUUID();
        when(repository.findById(missing, org)).thenReturn(Mono.empty());
        StepVerifier.create(new RetrieveConnectionUseCase(repository, secrets).execute(missing, org, null)).expectError(ConnectionNotFoundException.class).verify();
    }

    @Test
    @DisplayName("the audit log lists the entries, and refuses a requester or an unknown connection")
    void auditLog() {
        Connection connection = connection();
        when(repository.findById(connection.getId(), org)).thenReturn(Mono.just(connection));
        RetrieveConnectionAuditLogUseCase useCase = new RetrieveConnectionAuditLogUseCase(repository);

        StepVerifier.create(useCase.execute(connection.getId(), org, "AGENT"))
                .assertNext(entries -> {
                    assertEquals(1, entries.size());
                    assertEquals("INITIATED", entries.get(0).action());
                    assertNull(entries.get(0).fromStatus());
                }).verifyComplete();
        StepVerifier.create(useCase.execute(connection.getId(), org, "REQUESTER")).expectError(IntegrationAccessDeniedException.class).verify();
        UUID missing = UUID.randomUUID();
        when(repository.findById(missing, org)).thenReturn(Mono.empty());
        StepVerifier.create(useCase.execute(missing, org, null)).expectError(ConnectionNotFoundException.class).verify();
    }

    @Test
    @DisplayName("each secret is reported on its own: set one and not the other, in both directions")
    void secretsAreReportedIndependently() {
        Connection connection = connection();
        when(repository.findById(connection.getId(), org)).thenReturn(Mono.just(connection));
        when(repository.findAll(org)).thenReturn(Flux.just(connection));
        when(secrets.resolve("JIRA_AUTH")).thenReturn(null).thenReturn("x").thenReturn(null).thenReturn("x");
        when(secrets.resolve("JIRA_HOOK")).thenReturn("y").thenReturn(null).thenReturn("y").thenReturn(null);
        RetrieveConnectionUseCase one = new RetrieveConnectionUseCase(repository, secrets);
        RetrieveConnectionsUseCase all = new RetrieveConnectionsUseCase(repository, secrets);

        StepVerifier.create(one.execute(connection.getId(), org, null)).assertNext(r -> assertTrue(!r.secretConfigured() && r.webhookSecretConfigured())).verifyComplete();
        StepVerifier.create(one.execute(connection.getId(), org, null)).assertNext(r -> assertTrue(r.secretConfigured() && !r.webhookSecretConfigured())).verifyComplete();
        StepVerifier.create(all.execute(org, null)).assertNext(r -> assertTrue(!r.secretConfigured() && r.webhookSecretConfigured())).verifyComplete();
        StepVerifier.create(all.execute(org, null)).assertNext(r -> assertTrue(r.secretConfigured() && !r.webhookSecretConfigured())).verifyComplete();

        when(repository.existsByName("Jira prod", org)).thenReturn(Mono.just(false));
        when(hashService.generateSovereignId("connection-creation")).thenReturn(Mono.just(UUID.randomUUID()));
        when(repository.create(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));
        when(secrets.resolve("JIRA_AUTH")).thenReturn(null);
        when(secrets.resolve("JIRA_HOOK")).thenReturn("y");
        StepVerifier.create(new InitiateConnectionUseCase(hashService, repository, policy, secrets).execute(org, initiateRequest(BASE), "op", null))
                .assertNext(r -> assertTrue(!r.secretConfigured() && r.webhookSecretConfigured())).verifyComplete();
    }

    // ------------------------------------------------------------------ Check

    @Test
    @DisplayName("check reports a missing credentials variable without calling the provider")
    void checkWithoutSecret() {
        Connection connection = connection();
        when(repository.findById(connection.getId(), org)).thenReturn(Mono.just(connection));
        when(secrets.resolve("JIRA_AUTH")).thenReturn(null);
        when(secrets.resolve("JIRA_HOOK")).thenReturn("tok");

        StepVerifier.create(new CheckConnectionUseCase(repository, secrets, registry()).execute(connection.getId(), org, "AGENT"))
                .assertNext(check -> {
                    assertFalse(check.secretConfigured());
                    assertTrue(check.webhookSecretConfigured());
                    assertFalse(check.reachable());
                    assertTrue(check.problem().contains("JIRA_AUTH"));
                }).verifyComplete();
        verify(jiraPort, never()).ping(any(), any());
    }

    @Test
    @DisplayName("check pings the provider; a provider failure is a result, not an error")
    void checkPings() {
        Connection connection = connection();
        when(repository.findById(connection.getId(), org)).thenReturn(Mono.just(connection));
        when(secrets.resolve("JIRA_AUTH")).thenReturn("Basic abc");
        when(secrets.resolve("JIRA_HOOK")).thenReturn(null);
        when(jiraPort.ping(connection, "Basic abc")).thenReturn(Mono.empty()).thenReturn(Mono.error(new ExternalTicketingException("Jira refused checking the connection (HTTP 401).")));
        CheckConnectionUseCase useCase = new CheckConnectionUseCase(repository, secrets, registry());

        StepVerifier.create(useCase.execute(connection.getId(), org, null))
                .assertNext(check -> assertTrue(check.reachable() && check.problem() == null && !check.webhookSecretConfigured())).verifyComplete();
        StepVerifier.create(useCase.execute(connection.getId(), org, null))
                .assertNext(check -> assertTrue(!check.reachable() && check.secretConfigured() && check.problem().contains("401"))).verifyComplete();

        StepVerifier.create(useCase.execute(connection.getId(), org, "REQUESTER")).expectError(IntegrationAccessDeniedException.class).verify();
        UUID missing = UUID.randomUUID();
        when(repository.findById(missing, org)).thenReturn(Mono.empty());
        StepVerifier.create(useCase.execute(missing, org, null)).expectError(ConnectionNotFoundException.class).verify();
    }

    private ProviderRegistry registry() {
        when(jiraPort.provider()).thenReturn(Provider.JIRA);
        return new ProviderRegistry(List.of(jiraPort));
    }

    @Test
    @DisplayName("the registry answers the adapter of a provider and refuses one it has none for")
    void registryLookup() {
        ProviderRegistry registry = registry();

        assertEquals(jiraPort, registry.of(Provider.JIRA));
        assertThrows(IllegalStateException.class, () -> registry.of(Provider.SERVICENOW));
    }

    // ------------------------------------------------------------------ Mapper

    @Test
    @DisplayName("the mapper turns a request into a connection and an audit entry into a response")
    void mapper() {
        Connection connection = ConnectionMapper.toDomain(initiateRequest(" " + BASE + " "), UUID.randomUUID(), org, "op");
        assertEquals(BASE, connection.getBaseUrl());
        assertEquals(Map.of("NEW", "To Do").get("NEW"), connection.getOutboundStatus().get("NEW"));

        var entry = ConnectionMapper.toResponse(connection.getAuditTrail().get(0));
        assertNull(entry.fromStatus());
        assertEquals("ACTIVE", entry.toStatus());
        connection.disable("op");
        var disabled = ConnectionMapper.toResponse(connection.getAuditTrail().get(1));
        assertEquals("ACTIVE", disabled.fromStatus());

        assertThrows(InvocationTargetException.class, () -> {
            Constructor<ConnectionMapper> constructor = ConnectionMapper.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            constructor.newInstance();
        });
    }
}
