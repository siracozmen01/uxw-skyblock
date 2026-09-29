package com.uxplima.uxmskyblock.core.domain.stranger;

/**
 * How far a StrangerRealms island reaches: what its size gives it, and more for each member past the
 * first, up to a limit.
 *
 * @param perMember how many blocks each member past the first adds to the radius
 * @param maxRadius the furthest the island reaches however many members it has; keep it inside half
 *     the grid spacing, so two islands never meet
 */
public record ClaimGrowth(int perMember, int maxRadius) {

    public static final ClaimGrowth SHIPPED = new ClaimGrowth(8, 200);

    public ClaimGrowth {
        if (perMember < 0 || maxRadius < 1) {
            throw new IllegalArgumentException("per-member must not be negative and max-radius must be above 0");
        }
    }

    /** The radius of an island whose size gives it {@code base}, with {@code members} members. */
    public int radius(int base, int members) {
        long grown = (long) base + (long) perMember * Math.max(0, members - 1);
        return (int) Math.max(1, Math.min(Math.max(base, maxRadius), grown));
    }
}
