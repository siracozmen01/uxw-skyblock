package com.uxplima.uxmskyblock.rest.config;

import java.util.Objects;

public record RestConfiguration(boolean enabled, String host, int port, String bearerToken) {
    public RestConfiguration {
        Objects.requireNonNull(host, "host must not be null");
        Objects.requireNonNull(bearerToken, "bearerToken must not be null");
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port must be between 0 and 65535: " + port);
        }
    }

    public static RestConfiguration createDefault() {
        return new RestConfiguration(false, "127.0.0.1", 8080, "changeme-uxm-skyblock-secret");
    }
}
