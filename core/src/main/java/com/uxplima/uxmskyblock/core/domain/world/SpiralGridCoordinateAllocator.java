package com.uxplima.uxmskyblock.core.domain.world;

/**
 * Pure mathematical spiral coordinate generator for island grid allocation.
 *
 * <p>Generates discrete coordinates on an expanding square spiral grid where adjacent
 * points are separated by {@code gridSpacing} blocks (defaulting to 5,120 blocks
 * for Folia region thread isolation heuristics).
 */
public final class SpiralGridCoordinateAllocator {

    public static final int DEFAULT_GRID_SPACING = 5120;

    private final int gridSpacing;

    public SpiralGridCoordinateAllocator(int gridSpacing) {
        if (gridSpacing <= 0) {
            throw new IllegalArgumentException("gridSpacing must be positive: " + gridSpacing);
        }
        this.gridSpacing = gridSpacing;
    }

    public SpiralGridCoordinateAllocator() {
        this(DEFAULT_GRID_SPACING);
    }

    public int gridSpacing() {
        return gridSpacing;
    }

    /**
     * Maps a non-negative sequence index to grid coordinates.
     * Index 0 maps to (0, 0).
     */
    public IslandCoordinates coordinatesForIndex(long index) {
        if (index < 0) {
            throw new IllegalArgumentException("index must be non-negative: " + index);
        }
        if (index == 0) {
            return new IslandCoordinates(0, 0);
        }

        long k = (long) Math.ceil((Math.sqrt((double) (index + 1)) - 1) / 2.0);
        long innerMax = (2 * k - 1) * (2 * k - 1) - 1;
        long offset = index - innerMax;
        long sideLength = 2 * k;

        long side = (offset - 1) / sideLength;
        long step = (offset - 1) % sideLength;

        long gx;
        long gz;

        if (side == 0) {
            gx = k;
            gz = -(k - 1) + step;
        } else if (side == 1) {
            gx = (k - 1) - step;
            gz = k;
        } else if (side == 2) {
            gx = -k;
            gz = (k - 1) - step;
        } else {
            gx = -(k - 1) + step;
            gz = -k;
        }

        return new IslandCoordinates((int) (gx * gridSpacing), (int) (gz * gridSpacing));
    }

    /**
     * Maps grid coordinates back to the sequence index.
     * Returns -1 if (x, z) does not sit on the grid spacing.
     */
    public long indexForCoordinates(int x, int z) {
        if (x % gridSpacing != 0 || z % gridSpacing != 0) {
            return -1;
        }
        long gx = x / gridSpacing;
        long gz = z / gridSpacing;

        if (gx == 0 && gz == 0) {
            return 0;
        }

        long k = Math.max(Math.abs(gx), Math.abs(gz));
        long innerMax = (2 * k - 1) * (2 * k - 1) - 1;
        long sideLength = 2 * k;

        long offset;
        if (gx == k && gz > -k) {
            offset = (gz + k - 1) + 1;
        } else if (gz == k && gx < k) {
            offset = sideLength + ((k - 1) - gx) + 1;
        } else if (gx == -k && gz < k) {
            offset = 2 * sideLength + ((k - 1) - gz) + 1;
        } else {
            offset = 3 * sideLength + (gx + k - 1) + 1;
        }

        return innerMax + offset;
    }

    public boolean isGridPoint(int x, int z) {
        return indexForCoordinates(x, z) >= 0;
    }
}
