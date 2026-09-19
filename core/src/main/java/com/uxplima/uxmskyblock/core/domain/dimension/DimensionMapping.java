package com.uxplima.uxmskyblock.core.domain.dimension;

import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;
import org.jspecify.annotations.Nullable;

/**
 * Immutable mapping and gating specification for an island dimension (Section 2.37).
 *
 * @param dimensionType dimensional environment
 * @param mode private island, shared wilderness, or disabled
 * @param worldName target world identifier
 * @param requiredUpgrade progression upgrade required to unlock this dimension (e.g. island_nether, island_end)
 * @param schematic starter platform or outpost schematic/structure key
 */
public record DimensionMapping(
        IslandDimensionType dimensionType,
        DimensionMode mode,
        String worldName,
        @Nullable UpgradeId requiredUpgrade,
        @Nullable String schematic) {

    public DimensionMapping {
        Objects.requireNonNull(dimensionType, "dimensionType must not be null");
        Objects.requireNonNull(mode, "mode must not be null");
        Objects.requireNonNull(worldName, "worldName must not be null");
    }

    public boolean isPrivateIsland() {
        return mode == DimensionMode.PRIVATE_ISLAND;
    }

    public boolean isSharedWorld() {
        return mode == DimensionMode.SHARED_WORLD;
    }

    public boolean isEnabled() {
        return mode.isEnabled();
    }
}
