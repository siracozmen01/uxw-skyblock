package com.uxplima.uxmskyblock.core.domain.island;

/**
 * Axis-aligned bounding box coordinates representing an island's territory.
 */
public record IslandBounds(int minX, int minZ, int maxX, int maxZ, int centerX, int centerZ, int radius) {

    public IslandBounds {
        if (radius <= 0) {
            throw new IllegalArgumentException("radius must be positive: " + radius);
        }
        if (minX > maxX || minZ > maxZ) {
            throw new IllegalArgumentException("min coordinates must not exceed max coordinates");
        }
    }

    public static IslandBounds fromCenterAndRadius(int centerX, int centerZ, int radius) {
        if (radius <= 0) {
            throw new IllegalArgumentException("radius must be positive: " + radius);
        }
        return new IslandBounds(
                centerX - radius, centerZ - radius, centerX + radius, centerZ + radius, centerX, centerZ, radius);
    }

    public boolean contains(int x, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    public IslandBounds expand(int deltaRadius) {
        if (deltaRadius <= 0) {
            throw new IllegalArgumentException("deltaRadius must be positive: " + deltaRadius);
        }
        return fromCenterAndRadius(centerX, centerZ, radius + deltaRadius);
    }
}
