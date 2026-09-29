package com.uxplima.uxmskyblock.core.application.poseidon;

import java.util.Set;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/** Where the Poseidon islands are kept. */
public interface PoseidonIslandsPort {

    /** Every Poseidon island. */
    Set<IslandId> findAll();

    /** Whether the island is a Poseidon island. */
    boolean exists(IslandId islandId);

    /** Records an island as a Poseidon island. A second record changes nothing. */
    void add(IslandId islandId);
}
