package com.uxplima.uxmskyblock.core.domain.warp;

import java.time.Instant;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * Domain entity representing a persistent named island warp.
 */
public record IslandWarp(
        IslandWarpId id,
        IslandId islandId,
        WarpName name,
        WarpLocation location,
        String iconMaterial,
        WarpCategory category,
        boolean isLocked,
        Instant createdAt,
        Instant updatedAt) {

    public IslandWarp {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(location, "location must not be null");
        Objects.requireNonNull(iconMaterial, "iconMaterial must not be null");
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    public IslandWarp withLocked(boolean locked) {
        return new IslandWarp(id, islandId, name, location, iconMaterial, category, locked, createdAt, Instant.now());
    }

    public IslandWarp withLocation(WarpLocation newLocation) {
        return new IslandWarp(
                id, islandId, name, newLocation, iconMaterial, category, isLocked, createdAt, Instant.now());
    }

    public IslandWarp withCategory(WarpCategory newCategory) {
        return new IslandWarp(
                id, islandId, name, location, iconMaterial, newCategory, isLocked, createdAt, Instant.now());
    }

    public IslandWarp withIconMaterial(String newIconMaterial) {
        return new IslandWarp(
                id, islandId, name, location, newIconMaterial, category, isLocked, createdAt, Instant.now());
    }
}
