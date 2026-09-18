package com.uxplima.uxmskyblock.core.domain.warp;

import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

public class DuplicateWarpNameException extends RuntimeException {

    private final IslandId islandId;
    private final WarpName warpName;

    public DuplicateWarpNameException(IslandId islandId, WarpName warpName) {
        super("Island " + islandId + " already has a warp named '" + warpName + "'");
        this.islandId = Objects.requireNonNull(islandId, "islandId must not be null");
        this.warpName = Objects.requireNonNull(warpName, "warpName must not be null");
    }

    public IslandId islandId() {
        return islandId;
    }

    public WarpName warpName() {
        return warpName;
    }
}
