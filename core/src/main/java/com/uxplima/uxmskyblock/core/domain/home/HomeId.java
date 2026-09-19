package com.uxplima.uxmskyblock.core.domain.home;

import java.util.Objects;
import java.util.UUID;

/**
 * Value object representing a unique home identity.
 */
public record HomeId(UUID value) {
    public HomeId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static HomeId random() {
        return new HomeId(UUID.randomUUID());
    }

    public static HomeId fromString(String str) {
        return new HomeId(UUID.fromString(str));
    }
}
