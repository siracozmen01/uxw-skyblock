package com.uxplima.uxmskyblock.core.domain.limit;

import java.util.Objects;

/**
 * Types of tracked and hardware-capped blocks and entities per island (Section 2.31).
 */
public enum LimitType {
    // Tile Entities / Redstone
    HOPPER(LimitCategory.TILE_ENTITY),
    PISTON(LimitCategory.TILE_ENTITY),
    STICKY_PISTON(LimitCategory.TILE_ENTITY),
    OBSERVER(LimitCategory.TILE_ENTITY),
    DROPPER(LimitCategory.TILE_ENTITY),
    DISPENSER(LimitCategory.TILE_ENTITY),
    BREWING_STAND(LimitCategory.TILE_ENTITY),
    SPAWNER(LimitCategory.TILE_ENTITY),

    // Living Entities & Vehicles
    VILLAGER(LimitCategory.ENTITY),
    ARMOR_STAND(LimitCategory.ENTITY),
    MINECART(LimitCategory.ENTITY),
    BOAT(LimitCategory.ENTITY);

    private final LimitCategory category;

    LimitType(LimitCategory category) {
        this.category = Objects.requireNonNull(category, "category must not be null");
    }

    public LimitCategory category() {
        return category;
    }
}
