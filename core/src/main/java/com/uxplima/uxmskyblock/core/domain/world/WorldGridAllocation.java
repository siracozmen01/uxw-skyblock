package com.uxplima.uxmskyblock.core.domain.world;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * Immutable domain record representing a durable territorial world grid allocation.
 *
 * <p>Every island territory on the server cluster occupies a unique, monotonic sequence
 * slot and associated discrete center coordinates on the spiral world grid.
 */
public record WorldGridAllocation(
        long sequenceIndex,
        String worldName,
        int centerX,
        int centerZ,
        Optional<IslandId> islandId,
        ServerNodeId allocatedByNode,
        Instant allocatedAt) {

    public WorldGridAllocation {
        if (sequenceIndex < 0) {
            throw new IllegalArgumentException("sequenceIndex must be non-negative: " + sequenceIndex);
        }
        Objects.requireNonNull(worldName, "worldName must not be null");
        if (worldName.isBlank()) {
            throw new IllegalArgumentException("worldName must not be blank");
        }
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(allocatedByNode, "allocatedByNode must not be null");
        Objects.requireNonNull(allocatedAt, "allocatedAt must not be null");
    }
}
