package com.uxplima.uxmskyblock.core.domain.snapshot;

/**
 * How much of a backup a restore puts back.
 *
 * <p>The persistence specification names these three and the code restored everything it found,
 * which is how a restore could rewind an island bank to a balance the owner had already spent. No
 * mode rewinds money: not the bank, not the transaction history, not the vault pages, not a
 * player's inventory. A backup is for geometry and for the island's own shape, and the economy is
 * append only by design.
 */
public enum RestoreMode {

    /** Blocks and biomes only. No relational state is touched at all. */
    GEOMETRY_ONLY,

    /** The world, plus where the island is and the flags that protect it. Membership is left alone. */
    WORLD_CONTENT_SAFE,

    /** The world, the island's place and flags, and its membership, roles and upgrades. */
    FULL_ISLAND;

    /** Whether this mode writes any relational row at all. */
    public boolean restoresRelationalState() {
        return this != GEOMETRY_ONLY;
    }

    /** Whether this mode puts back who belongs to the island and what they may do. */
    public boolean restoresMembership() {
        return this == FULL_ISLAND;
    }

    /**
     * Whether this mode brings back the island's non-economic creatures, the animals and mobs that
     * carry nothing. The specification's geometry only mode restores blocks and biomes alone; the
     * other two restore geometry and the entities that hold no item.
     */
    public boolean restoresEntities() {
        return this != GEOMETRY_ONLY;
    }

    /** The mode a caller gets when they name none: the one that changes least. */
    public static RestoreMode safeDefault() {
        return WORLD_CONTENT_SAFE;
    }
}
