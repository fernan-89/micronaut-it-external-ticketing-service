package com.thinklab.infrastructure.adapter.out.secret;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EnvSecretResolverTest {

    private final EnvSecretResolver resolver = new EnvSecretResolver(Map.of("SET", "value", "BLANK", "  ")::get);

    @Test
    @DisplayName("the NAME of a secret resolves to the value of that variable; unset, blank and null names are 'not configured'")
    void resolves() {
        assertEquals("value", resolver.resolve("SET"));
        assertNull(resolver.resolve("BLANK"));
        assertNull(resolver.resolve("MISSING"));
        assertNull(resolver.resolve(null));
    }

    @Test
    @DisplayName("the default resolver reads the process environment")
    void environment() {
        assertNull(new EnvSecretResolver().resolve("THINKLAB_SURELY_NOT_SET_" + System.nanoTime()));
    }
}
