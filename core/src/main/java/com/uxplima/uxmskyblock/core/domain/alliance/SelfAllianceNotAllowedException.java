package com.uxplima.uxmskyblock.core.domain.alliance;

import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * Thrown when an island attempts to ally with or invite itself.
 */
public final class SelfAllianceNotAllowedException extends RuntimeException {

    private final IslandId islandId;

    public SelfAllianceNotAllowedException(IslandId islandId) {
        super("Island cannot establish an alliance with itself: " + Objects.requireNonNull(islandId, "islandId"));
        this.islandId = islandId;
    }

    public IslandId islandId() {
        return islandId;
    }
}
