package com.uxplima.uxmskyblock.core.application.brix;

import java.util.Set;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/** Where the Brix plots are kept. */
public interface BrixPlotsPort {

    /** Every Brix plot. */
    Set<IslandId> findAll();

    /** Whether the island is a Brix plot. */
    boolean exists(IslandId islandId);

    /** Records an island as a Brix plot. A second record changes nothing. */
    void add(IslandId islandId);
}
