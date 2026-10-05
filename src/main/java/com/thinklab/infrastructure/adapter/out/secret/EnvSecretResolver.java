package com.thinklab.infrastructure.adapter.out.secret;

import com.thinklab.domain.port.SecretResolverPort;
import jakarta.inject.Singleton;

import java.util.function.Function;

/** Resolves the NAME of a secret to the value of that environment variable (ADR-032); a variable that is unset or blank is "not configured". */
@Singleton
public class EnvSecretResolver implements SecretResolverPort {

    private final Function<String, String> environment;

    public EnvSecretResolver() {
        this(System::getenv);
    }

    EnvSecretResolver(Function<String, String> environment) {
        this.environment = environment;
    }

    @Override
    public String resolve(String name) {
        String value = name == null ? null : environment.apply(name);
        return value == null || value.isBlank() ? null : value;
    }
}
