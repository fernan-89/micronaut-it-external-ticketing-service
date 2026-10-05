package com.thinklab.application.config;

import io.micronaut.context.annotation.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings of the integration (ADR-032). {@code insecureHosts} lists the hosts a connection may reach over plain http or at a private
 * address: empty in a real deployment (https only, no private literals), set to the host of the provider double in a test environment.
 */
@ConfigurationProperties("thinklab.external-ticketing")
public class ExternalTicketingProperties {

    private List<String> insecureHosts = new ArrayList<>();

    public List<String> getInsecureHosts() {
        return insecureHosts;
    }

    public void setInsecureHosts(List<String> insecureHosts) {
        this.insecureHosts = insecureHosts;
    }
}
