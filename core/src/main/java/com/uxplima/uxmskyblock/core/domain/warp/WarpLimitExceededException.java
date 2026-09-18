package com.uxplima.uxmskyblock.core.domain.warp;

import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

public class WarpLimitExceededException extends RuntimeException {

    private final IslandId islandId;
    private final int currentCount;
    private final int maxAllowed;

    public WarpLimitExceededException(IslandId islandId, int currentCount, int maxAllowed) {
        super("Island " + islandId + " has reached its warp limit (" + currentCount + "/" + maxAllowed + ")");
        this.islandId = Objects.requireNonNull(islandId, "islandId must not be null");
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
