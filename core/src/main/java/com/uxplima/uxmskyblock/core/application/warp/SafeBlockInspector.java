package com.uxplima.uxmskyblock.core.application.warp;

/**
 * Decoupled block inspection port for verifying teleport destination safety.
 */
public interface SafeBlockInspector {

    /**
     * Checks whether the block at the given coordinate provides a solid, safe surface to stand upon.
     */
    boolean isSolidFloor(String worldName, int x, int y, int z);

    /**
     * Checks whether the block at the given coordinate is passable for a player's body and head.
     */
    boolean isPassable(String worldName, int x, int y, int z);

    /**
     * Checks whether the block at the given coordinate is hazardous (e.g. lava, fire, cactus, wither rose).
     */
    boolean isHazardous(String worldName, int x, int y, int z);

    /**
     * Verifies that the y-coordinate is within acceptable world boundaries.
     */
    default boolean isWithinWorldBounds(String worldName, int y) {
        return y >= -64 && y <= 320;
    }
}
