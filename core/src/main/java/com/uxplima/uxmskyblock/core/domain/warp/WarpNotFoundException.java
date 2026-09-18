package com.uxplima.uxmskyblock.core.domain.warp;

import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

public class WarpNotFoundException extends RuntimeException {

    private final IslandId islandId;
    private final String warpIdentifier;

    public WarpNotFoundException(IslandId islandId, String warpIdentifier) {
        super("Warp '" + warpIdentifier + "' not found on island " + islandId);
        this.islandId = Objects.requireNonNull(islandId, "islandId must not be null");
        this.warpIdentifier = Objects.requireNonNull(warpIdentifier, "warpIdentifier must not be null");
    }

    public IslandId islandId() {
        return islandId;
    }

    public String warpIdentifier() {
        return warpIdentifier;
    }
}
