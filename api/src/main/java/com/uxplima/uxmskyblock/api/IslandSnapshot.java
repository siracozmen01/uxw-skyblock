package com.uxplima.uxmskyblock.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable public snapshot of an island.
 */
public record IslandSnapshot(UUID islandId, UUID ownerUuid, int minX, int minZ, int maxX, int maxZ, Instant createdAt) {

    public IslandSnapshot {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(ownerUuid, "ownerUuid must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
