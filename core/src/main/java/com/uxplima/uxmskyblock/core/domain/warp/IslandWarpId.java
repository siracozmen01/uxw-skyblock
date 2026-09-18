package com.uxplima.uxmskyblock.core.domain.warp;

import java.util.Objects;
import java.util.UUID;

/**
 * Value object wrapping a unique island warp identifier.
 */
public record IslandWarpId(UUID value) {

    public IslandWarpId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static IslandWarpId of(UUID value) {
        return new IslandWarpId(value);
    }

    public static IslandWarpId random() {
        return new IslandWarpId(UUID.randomUUID());
    }

    public static IslandWarpId fromString(String uuidString) {
        Objects.requireNonNull(uuidString, "uuidString must not be null");
        return new IslandWarpId(UUID.fromString(uuidString));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
