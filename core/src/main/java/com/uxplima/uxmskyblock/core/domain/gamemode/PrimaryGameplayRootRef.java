package com.uxplima.uxmskyblock.core.domain.gamemode;

import java.time.Instant;
import java.util.Objects;

/**
 * Canonical linkage connecting a GameModeInstance to its primary gameplay aggregate root (e.g. IslandId for Skyblock).
 */
public record PrimaryGameplayRootRef(
        GameModeInstanceId gameModeInstanceId,
        String rootId,
        String rootType,
        Instant boundAt) {

    public PrimaryGameplayRootRef {
        Objects.requireNonNull(gameModeInstanceId, "gameModeInstanceId must not be null");
        Objects.requireNonNull(rootId, "rootId must not be null");
        Objects.requireNonNull(rootType, "rootType must not be null");
        Objects.requireNonNull(boundAt, "boundAt must not be null");
    }

    public static PrimaryGameplayRootRef forIsland(GameModeInstanceId instanceId, String islandId, Instant now) {
        return new PrimaryGameplayRootRef(instanceId, islandId, "ISLAND", now);
    }
}
