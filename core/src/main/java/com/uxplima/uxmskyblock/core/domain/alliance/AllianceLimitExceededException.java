package com.uxplima.uxmskyblock.core.domain.alliance;

import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * Thrown when an island has reached its maximum allowed allied islands capacity.
 */
public final class AllianceLimitExceededException extends RuntimeException {

    private final IslandId islandId;
    private final int currentCount;
    private final int maxAllowed;

    public AllianceLimitExceededException(IslandId islandId, int currentCount, int maxAllowed) {
        super("Island " + Objects.requireNonNull(islandId, "islandId") + " has reached maximum alliance limit: current="
                + currentCount + ", max=" + maxAllowed);
        this.islandId = islandId;
        this.currentCount = currentCount;
        this.maxAllowed = maxAllowed;
    }

    public IslandId islandId() {
        return islandId;
    }

    public int currentCount() {
        return currentCount;
    }

    public int maxAllowed() {
        return maxAllowed;
    }
}
