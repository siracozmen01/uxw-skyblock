package com.uxplima.uxmskyblock.core.application.stranger;

import java.util.Set;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/** Where the StrangerRealms islands are kept. */
public interface StrangerRealmsPort {

    /** Every StrangerRealms island. */
    Set<IslandId> findAll();

    /** Whether the island is a StrangerRealms island. */
    boolean exists(IslandId islandId);

    /** Records an island as a StrangerRealms island. A second record changes nothing. */
    void add(IslandId islandId);
}
