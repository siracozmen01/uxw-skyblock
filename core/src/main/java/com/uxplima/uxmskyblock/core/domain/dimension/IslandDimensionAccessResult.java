package com.uxplima.uxmskyblock.core.domain.dimension;

import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;

/**
 * Result outcome of an island dimension access evaluation (Sections 2.27 & 2.37).
 */
public sealed interface IslandDimensionAccessResult {

    /**
     * Access is permitted to the target dimension.
     */
    record Allowed(IslandDimensionType dimensionType, DimensionMode mode, String targetWorld, boolean schematicRequired)
            implements IslandDimensionAccessResult {
        public Allowed {
            Objects.requireNonNull(dimensionType, "dimensionType must not be null");
            Objects.requireNonNull(mode, "mode must not be null");
            Objects.requireNonNull(targetWorld, "targetWorld must not be null");
        }
    }

    /**
     * Access is locked pending purchase of the required island upgrade.
     */
    record Locked(IslandDimensionType dimensionType, UpgradeId requiredUpgrade) implements IslandDimensionAccessResult {
        public Locked {
            Objects.requireNonNull(dimensionType, "dimensionType must not be null");
            Objects.requireNonNull(requiredUpgrade, "requiredUpgrade must not be null");
        }
    }

    /**
     * Cross-dimension travel to this dimension is disabled on this server.
     */
    record Disabled(IslandDimensionType dimensionType) implements IslandDimensionAccessResult {
        public Disabled {
            Objects.requireNonNull(dimensionType, "dimensionType must not be null");
        }
    }

    /**
     * The player does not belong to any active island.
     */
    record NoIsland() implements IslandDimensionAccessResult {}
}
