package com.uxplima.uxmskyblock.core.domain.dimension;

/**
 * Operational mode governing multi-dimensional access and territory mapping (Section 2.37).
 */
public enum DimensionMode {
    /**
     * Coordinate grid mirrors 1-to-1 to a dedicated dimension world (e.g. skyblock_nether).
     * Platform/outpost is generated dynamically at island coordinates.
     */
    PRIVATE_ISLAND,

    /**
     * Portals route players to a centralized, shared wilderness dimension world with common spawn.
     */
    SHARED_WORLD,

    /**
     * Portal access to this dimension is completely prohibited.
     */
    DISABLED;

    public boolean isEnabled() {
        return this != DISABLED;
    }
}
