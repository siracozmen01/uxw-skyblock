package com.uxplima.uxmskyblock.core.application.acid;

import java.util.Map;
import java.util.OptionalInt;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/** Where the AcidIsland islands and their sea levels are kept. */
public interface AcidIslandsPort {

    /** Every AcidIsland island and the level its sea's surface stands at. */
    Map<IslandId, Integer> findAll();

    /** The level of one island's sea, or empty when the island is not an AcidIsland island. */
    OptionalInt find(IslandId islandId);

    /** Records an island as an AcidIsland island. A second record keeps the first level. */
    void add(IslandId islandId, int seaLevel);
}
