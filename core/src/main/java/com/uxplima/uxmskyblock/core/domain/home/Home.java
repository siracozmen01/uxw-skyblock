package com.uxplima.uxmskyblock.core.domain.home;

import java.time.Instant;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;

/**
 * Domain entity representing a named island home location (Section 2.42).
 */
public record Home(
        HomeId id,
        ProfileId ownerProfileId,
        IslandId islandId,
        String name,
        HomeScope scope,
        String worldName,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        Instant createdAt,
        Instant updatedAt) {

    public Home {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(ownerProfileId, "ownerProfileId must not be null");
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(worldName, "worldName must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }
}
