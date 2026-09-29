package com.uxplima.uxmskyblock.core.domain.stranger;

/**
 * How wide the border of the StrangerRealms land is: as wide as it takes to hold the furthest island
 * with room around it, and never narrower or wider than the operator's limits. It grows as islands are
 * made anywhere on the network.
 *
 * @param margin the room kept beyond the furthest island's edge, in blocks
 * @param minimum the narrowest the border is, from side to side
 * @param maximum the widest the border is, from side to side
 */
public record RealmBorder(int margin, int minimum, int maximum) {

    public static final RealmBorder SHIPPED = new RealmBorder(512, 2048, 29_999_984);

    public RealmBorder {
        if (margin < 0 || minimum < 1 || maximum < minimum) {
            throw new IllegalArgumentException(
                    "the margin must not be negative, and the minimum must be above 0 and not above the maximum");
        }
    }

    /** The width, side to side, that holds an island reaching {@code reach} from the centre. */
    public double size(int reach) {
        long wanted = 2L * ((long) Math.max(0, reach) + margin);
        return Math.clamp(wanted, minimum, maximum);
    }
}
