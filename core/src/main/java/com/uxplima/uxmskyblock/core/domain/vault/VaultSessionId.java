package com.uxplima.uxmskyblock.core.domain.vault;

import java.util.Objects;
import java.util.UUID;

/**
 * Strongly typed value object representing the unique identifier of an active vault edit session.
 */
public record VaultSessionId(UUID value) {

    public VaultSessionId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static VaultSessionId random() {
        return new VaultSessionId(UUID.randomUUID());
    }

    public static VaultSessionId fromString(String uuidString) {
        Objects.requireNonNull(uuidString, "uuidString must not be null");
        return new VaultSessionId(UUID.fromString(uuidString));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
