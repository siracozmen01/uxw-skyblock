package com.uxplima.uxmskyblock.core.application.warp;

import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.warp.UnsafeTeleportDestinationException;
import com.uxplima.uxmskyblock.core.domain.warp.WarpLocation;

/**
 * Enterprise engine validating teleport destination safety and locating nearest safe air columns
 * with solid bases within a bounding box to prevent visitor trapping or lethal drops.
 */
public final class SafeTeleportEngine {

    public static final int DEFAULT_SEARCH_RADIUS = 5;
    public static final String ERROR_UNSAFE_DESTINATION = "teleport.unsafe_destination";

    private final int searchRadius;

    public SafeTeleportEngine() {
        this(DEFAULT_SEARCH_RADIUS);
    }

    public SafeTeleportEngine(int searchRadius) {
        if (searchRadius < 0) {
            throw new IllegalArgumentException("searchRadius cannot be negative: " + searchRadius);
        }
        this.searchRadius = searchRadius;
    }

    public int searchRadius() {
        return searchRadius;
    }

    /**
     * Evaluates a target destination. If the exact requested destination is safe, returns it.
     * If unsafe, attempts to find the nearest valid safe air column with a solid base within the search radius.
     * If no safe spot is found, throws {@link UnsafeTeleportDestinationException}.
     *
     * @param requested the target destination requested
     * @param inspector the block inspector port
     * @return verified safe destination location
     * @throws UnsafeTeleportDestinationException if the location and all alternatives in the radius are unsafe
     */
    public WarpLocation verifyOrFindSafeSpot(WarpLocation requested, SafeBlockInspector inspector) {
        Objects.requireNonNull(requested, "requested must not be null");
        Objects.requireNonNull(inspector, "inspector must not be null");

        int bx = requested.blockX();
        int by = requested.blockY();
        int bz = requested.blockZ();
        String world = requested.worldName();

        if (isSpotSafe(world, bx, by, bz, inspector)) {
            return requested;
        }

        WarpLocation safeAlternative =
                findNearestSafeSpot(world, bx, by, bz, requested.yaw(), requested.pitch(), inspector);
        if (safeAlternative != null) {
            return safeAlternative;
        }

        throw new UnsafeTeleportDestinationException(requested, ERROR_UNSAFE_DESTINATION);
    }

    /**
     * Checks whether a specific block position (bx, by, bz) represents a safe standing position:
     * <ul>
     *   <li>(bx, by - 1, bz) must be within bounds, solid floor, and not hazardous.</li>
     *   <li>(bx, by, bz) must be within bounds, passable, and not hazardous.</li>
     *   <li>(bx, by + 1, bz) must be within bounds, passable, and not hazardous.</li>
     * </ul>
     */
    public boolean isSpotSafe(String world, int bx, int by, int bz, SafeBlockInspector inspector) {
        if (!inspector.isWithinWorldBounds(world, by - 1)
                || !inspector.isWithinWorldBounds(world, by)
                || !inspector.isWithinWorldBounds(world, by + 1)) {
            return false;
        }

        // Floor check: y - 1
        if (!inspector.isSolidFloor(world, bx, by - 1, bz) || inspector.isHazardous(world, bx, by - 1, bz)) {
            return false;
        }

        // Feet / Torso check: y
        if (!inspector.isPassable(world, bx, by, bz) || inspector.isHazardous(world, bx, by, bz)) {
            return false;
        }

        // Head check: y + 1
        if (!inspector.isPassable(world, bx, by + 1, bz) || inspector.isHazardous(world, bx, by + 1, bz)) {
            return false;
        }

        return true;
    }

    private WarpLocation findNearestSafeSpot(
            String world, int originX, int originY, int originZ, float yaw, float pitch, SafeBlockInspector inspector) {
        Candidate best = null;
        int r = searchRadius;

        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    int tx = originX + dx;
                    int ty = originY + dy;
                    int tz = originZ + dz;

                    if (isSpotSafe(world, tx, ty, tz, inspector)) {
                        int distSq = dx * dx + dy * dy + dz * dz;
                        if (best == null || distSq < best.distSq) {
                            best = new Candidate(tx, ty, tz, distSq);
                        }
                    }
                }
            }
        }

        if (best == null) {
            return null;
        }

        return new WarpLocation(world, best.x + 0.5, best.y, best.z + 0.5, yaw, pitch);
    }

    private record Candidate(int x, int y, int z, int distSq) {}
}
