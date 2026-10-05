package com.thinklab.infrastructure.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard found by the first live E2E run: the readiness probe used to GET the bare Hash
 * Token Registry base URL, which answers 404, so every consumer reported DOWN (and a Kubernetes pod
 * would never become ready). The probe must target an endpoint that really exists.
 */
class WarmupEndpointsConfigTest {

    private static final Pattern WARMUP_HASH_PROBE =
            Pattern.compile("warmup:\\s+endpoints:\\s+hash-service:[^\\n]*/health/liveness", Pattern.DOTALL);

    @Test
    @DisplayName("the hash-service dependency probe targets its liveness endpoint, not the bare base URL")
    void hashServiceProbeTargetsLiveness() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/application.yml")) {
            assertNotNull(in, "application.yml must be on the classpath");
            String yml = new String(in.readAllBytes(), StandardCharsets.UTF_8);

            assertTrue(WARMUP_HASH_PROBE.matcher(yml).find(),
                    "warmup.endpoints.hash-service must probe /health/liveness");
        }
    }
}
