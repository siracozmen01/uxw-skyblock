package com.uxplima.uxmskyblock.core.domain.island;

import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * Spatial location and spawn point definition for an Island.
 */
public record IslandLocation(
        IslandId islandId,
        String worldName,
        IslandBounds bounds,
        double spawnX,
        double spawnY,
        double spawnZ,
        float spawnYaw,
        float spawnPitch) {

    public IslandLocation {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(worldName, "worldName must not be null");
        if (worldName.isBlank()) {
            throw new IllegalArgumentException("worldName must not be blank");
        }
        Objects.requireNonNull(bounds, "bounds must not be null");
    }

    public static IslandLocation fromCenterAndRadius(
            IslandId islandId, String worldName, int centerX, int centerZ, int radius) {
        IslandBounds bounds = IslandBounds.fromCenterAndRadius(centerX, centerZ, radius);
        return new IslandLocation(islandId, worldName, bounds, centerX + 0.5, 100.0, centerZ + 0.5, 0.0f, 0.0f);
    }
}
