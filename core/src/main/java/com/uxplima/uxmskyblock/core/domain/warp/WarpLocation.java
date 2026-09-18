package com.uxplima.uxmskyblock.core.domain.warp;

import java.util.Objects;

/**
 * Spatial coordinate and orientation for a warp destination.
 */
public record WarpLocation(String worldName, double x, double y, double z, float yaw, float pitch) {

    public WarpLocation {
        Objects.requireNonNull(worldName, "worldName must not be null");
        if (worldName.isBlank()) {
            throw new IllegalArgumentException("worldName must not be blank");
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Coordinates must be finite numbers");
        }
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("Yaw and pitch must be finite numbers");
        }
    }

    public int blockX() {
        return (int) Math.floor(x);
    }

    public int blockY() {
        return (int) Math.floor(y);
    }

    public int blockZ() {
        return (int) Math.floor(z);
    }

    public WarpLocation withCoordinates(double newX, double newY, double newZ) {
        return new WarpLocation(worldName, newX, newY, newZ, yaw, pitch);
    }
}
