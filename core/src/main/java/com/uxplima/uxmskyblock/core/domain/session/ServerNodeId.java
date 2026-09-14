package com.uxplima.uxmskyblock.core.domain.session;

import java.util.Objects;

/**
 * Strongly typed value object representing a backend cluster server node identifier.
 *
 * @param value the non-blank node identifier string
 */
public record ServerNodeId(String value) {

    public ServerNodeId {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("node identifier must not be blank");
        }
    }

    public static ServerNodeId of(String value) {
        return new ServerNodeId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
