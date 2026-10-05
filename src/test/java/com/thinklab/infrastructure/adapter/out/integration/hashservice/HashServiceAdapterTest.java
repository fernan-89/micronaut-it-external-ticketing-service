package com.thinklab.infrastructure.adapter.out.integration.hashservice;

import com.thinklab.infrastructure.adapter.out.integration.hashservice.HashServiceAdapter.HashTokenResponse;
import com.thinklab.infrastructure.adapter.out.integration.hashservice.HashServiceAdapter.InitiateHashRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HashServiceAdapterTest {

    @Mock private HashApiClient apiClient;

    private HashServiceAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new HashServiceAdapter(apiClient);
    }

    @Test
    @DisplayName("generateSovereignId should call initiate with this service's identity headers and return the token id")
    void generateSovereignId() {
        UUID sovereign = UUID.randomUUID();
        when(apiClient.initiate(any(), any(), any(), any())).thenReturn(Mono.just(new HashTokenResponse(sovereign, "hash")));

        StepVerifier.create(adapter.generateSovereignId("ticketLink-creation"))
                .expectNext(sovereign)
                .verifyComplete();

        ArgumentCaptor<InitiateHashRequest> captor = ArgumentCaptor.forClass(InitiateHashRequest.class);
        verify(apiClient).initiate(eq("it-external-ticketing"), eq("it-external-ticketing-service"), eq("system"), captor.capture());
        assertTrue(captor.getValue().payload().startsWith("ticketLink-creation::"));
        assertEquals("SHA3_512", captor.getValue().algorithm());
        assertFalse(captor.getValue().asSerialKey());
    }

    @Test
    @DisplayName("generateSovereignId should hide infrastructure failures behind a dependency-failure error")
    void generateSovereignIdFailure() {
        when(apiClient.initiate(any(), any(), any(), any())).thenReturn(Mono.error(new RuntimeException("connection refused")));

        StepVerifier.create(adapter.generateSovereignId("ticketLink-creation"))
                .expectErrorSatisfies(error -> {
                    assertInstanceOf(IllegalStateException.class, error);
                    assertTrue(error.getMessage().contains("Hash Token Registry Service is currently unavailable"));
                    assertEquals("connection refused", error.getCause().getMessage());
                })
                .verify();
    }

    @Test
    @DisplayName("hashSensitiveData should send the raw payload and return the generated hash")
    void hashSensitiveData() {
        when(apiClient.initiate(any(), any(), any(), any())).thenReturn(Mono.just(new HashTokenResponse(UUID.randomUUID(), "deadbeef")));

        StepVerifier.create(adapter.hashSensitiveData("secret"))
                .expectNext("deadbeef")
                .verifyComplete();

        ArgumentCaptor<InitiateHashRequest> captor = ArgumentCaptor.forClass(InitiateHashRequest.class);
        verify(apiClient).initiate(any(), any(), any(), captor.capture());
        assertEquals("secret", captor.getValue().payload());
    }

    @Test
    @DisplayName("hashSensitiveData should hide infrastructure failures behind a dependency-failure error")
    void hashSensitiveDataFailure() {
        when(apiClient.initiate(any(), any(), any(), any())).thenReturn(Mono.error(new RuntimeException("timeout")));

        StepVerifier.create(adapter.hashSensitiveData("secret"))
                .expectErrorSatisfies(error -> {
                    assertInstanceOf(IllegalStateException.class, error);
                    assertTrue(error.getMessage().contains("cryptographic operations are unavailable"));
                })
                .verify();
    }
}
