package com.uxplima.uxmskyblock.core.application.island;

import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;

/**
 * Outbound application port for persistent Island entity and spatial location storage.
 */
public interface IslandStoragePort {

    /**
     * Persists or updates the complete Island aggregate along with its spatial location.
     *
     * @param island the island aggregate root
     * @param location the spatial location and bounds
     */
    void saveIsland(Island island, IslandLocation location);

    /**
     * Retrieves an Island aggregate by its unique identity.
     *
     * @param id the island id
     * @return optional containing the island aggregate if found
     */
    Optional<Island> findIslandById(IslandId id);

    /**
     * Retrieves the spatial location record for an Island.
     *
     * @param id the island id
     * @return optional containing the spatial location if found
     */
    Optional<IslandLocation> findLocationByIslandId(IslandId id);

    /**
     * Resolves the IslandId that a given profile is a member of.
     *
     * @param profileId the profile id
     * @return optional containing the island id if the profile belongs to an island
     */
    Optional<IslandId> findIslandIdByProfileId(ProfileId profileId);

    /**
     * Deletes an island and all its cascaded entities (locations, members, roles, flags, authorities).
     *
     * @param id the island id to delete
     */
    void deleteIsland(IslandId id);
}
