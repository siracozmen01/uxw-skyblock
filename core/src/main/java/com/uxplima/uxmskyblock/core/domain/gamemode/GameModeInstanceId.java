package com.uxplima.uxmskyblock.core.domain.gamemode;

import java.util.Objects;
import java.util.UUID;

/**
 * Strongly-typed value object representing a GameModeInstance identifier.
 */
public record GameModeInstanceId(UUID value) {

    public GameModeInstanceId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static GameModeInstanceId random() {
        return new GameModeInstanceId(UUID.randomUUID());
    }

    public static GameModeInstanceId of(UUID value) {
        return new GameModeInstanceId(value);
    }
}
