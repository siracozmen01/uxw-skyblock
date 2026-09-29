package com.uxplima.uxmskyblock.core.domain.grid;

/**
 * Where the blocks of a SkyGrid island stand: one every {@code spacing} blocks along each axis, lined
 * up on the block players arrive standing on, as far as {@code radius} sideways, {@code below} down and
 * {@code above} up from it.
 *
 * @param spacing how many blocks from one grid block to the next, along each axis
 * @param radius how far the grid reaches sideways from the island's centre
 * @param below how far it reaches down from the arrival
 * @param above how far it reaches up from the arrival
 */
public record GridLayout(int spacing, int radius, int below, int above) {

    public static final GridLayout SHIPPED = new GridLayout(4, 48, 64, 32);

    public GridLayout {
        if (spacing < 2 || spacing > 16) {
            throw new IllegalArgumentException("the grid's spacing is between 2 and 16: " + spacing);
        }
        if (radius < spacing || below < 0 || above < 0) {
            throw new IllegalArgumentException("the grid reaches at least one step sideways");
        }
    }

    /** Whether a grid block stands at the offset from the arrival, which is itself a grid block. */
    public boolean isNode(int dx, int dy, int dz) {
        return Math.floorMod(dx, spacing) == 0
                && Math.floorMod(dy, spacing) == 0
                && Math.floorMod(dz, spacing) == 0
                && Math.abs(dx) <= radius
                && Math.abs(dz) <= radius
                && dy >= -below
                && dy <= above;
    }

    /** The first offset at or after {@code from} that lines up with the grid. */
    public int alignUp(int from) {
        return from + Math.floorMod(-from, spacing);
    }
}
