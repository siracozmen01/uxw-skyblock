package com.uxplima.uxmskyblock.core.domain.alliance;

import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * Thrown when two islands attempt to ally or send an invite while already allied.
 */
public final class AlreadyAlliedException extends RuntimeException {

    private final IslandId islandA;
    private final IslandId islandB;

    public AlreadyAlliedException(IslandId islandA, IslandId islandB) {
        super("Islands " + Objects.requireNonNull(islandA, "islandA") + " and "
                + Objects.requireNonNull(islandB, "islandB") + " are already allied");
        this.islandA = islandA;
        this.islandB = islandB;
    }

    public IslandId islandA() {
        return islandA;
    }

    public IslandId islandB() {
        return islandB;
    }
}
