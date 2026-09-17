package com.uxplima.uxmskyblock.core.domain.world;

/**
 * Strongly typed 2D block coordinates for an island grid point.
 *
 * @param x center block X coordinate
 * @param z center block Z coordinate
 */
public record IslandCoordinates(int x, int z) {

    public static IslandCoordinates of(int x, int z) {
        return new IslandCoordinates(x, z);
    }
}
