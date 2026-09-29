package com.uxplima.uxmskyblock.core.application.stranger;

import java.util.Objects;

import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.stranger.ClaimGrowth;

/** How far a StrangerRealms island reaches now, with the members it has. */
public final class StrangerClaims {

    private final StrangerRealmsService service;
    private final IslandStoragePort islands;
    private final ClaimGrowth growth;

    public StrangerClaims(StrangerRealmsService service, IslandStoragePort islands, ClaimGrowth growth) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.islands = Objects.requireNonNull(islands, "islands must not be null");
        this.growth = Objects.requireNonNull(growth, "growth must not be null");
    }

    /**
     * How far the island reaches when its size gives it {@code base}: grown by its members for a
     * StrangerRealms island, {@code base} for any other. Reads the island, so off the main thread.
     */
    public int radius(IslandId islandId, int base) {
        if (!service.isStranger(islandId)) {
            return base;
        }
        int members = islands.findIslandById(islandId)
                .map(island -> island.members().size())
                .orElse(1);
        return growth.radius(base, members);
    }
}
