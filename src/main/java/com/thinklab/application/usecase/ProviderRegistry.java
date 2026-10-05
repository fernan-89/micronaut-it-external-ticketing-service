package com.thinklab.application.usecase;

import com.thinklab.domain.model.Connection.Provider;
import com.thinklab.domain.port.ExternalTicketingPort;
import jakarta.inject.Singleton;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Picks the adapter of the provider a connection talks to (ADR-030): one adapter per provider, found by the provider it declares. */
@Singleton
public class ProviderRegistry {

    private final Map<Provider, ExternalTicketingPort> byProvider = new EnumMap<>(Provider.class);

    public ProviderRegistry(List<ExternalTicketingPort> adapters) {
        adapters.forEach(adapter -> byProvider.put(adapter.provider(), adapter));
    }

    public ExternalTicketingPort of(Provider provider) {
        ExternalTicketingPort adapter = byProvider.get(provider);
        if (adapter == null) {
            throw new IllegalStateException("No adapter for provider " + provider);
        }
        return adapter;
    }
}
