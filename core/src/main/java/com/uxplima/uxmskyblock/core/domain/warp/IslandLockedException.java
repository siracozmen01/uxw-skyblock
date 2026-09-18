package com.uxplima.uxmskyblock.core.domain.warp;

import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

public class IslandLockedException extends RuntimeException {

    private final IslandId islandId;

    public IslandLockedException(IslandId islandId) {
        super("Island " + islandId + " is locked to visitors");
        this.islandId = Objects.requireNonNull(islandId, "islandId must not be null");
    }

    public IslandId islandId() {
        return islandId;
    }
}
