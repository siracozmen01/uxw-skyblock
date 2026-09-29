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

    /**
     * How far from the grid's centre, along x or z, the edge of the furthest StrangerRealms island lies,
     * across every server that shares the database. Zero when there is none.
     */
    int farthestReach();
}
