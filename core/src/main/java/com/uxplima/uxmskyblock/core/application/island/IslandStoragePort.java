package com.uxplima.uxmskyblock.core.application.island;

import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.event.StagedOutboxEvent;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import org.jspecify.annotations.Nullable;

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
     * Persists or updates the complete Island aggregate along with its spatial location and an optional outbox event.
     *
     * @param island the island aggregate root
     * @param location the spatial location and bounds
     * @param outboxEvent optional event to stage atomically in the same transaction
     */
    default void saveIsland(Island island, IslandLocation location, @Nullable StagedOutboxEvent outboxEvent) {
        saveIsland(island, location);
    }

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

    /**
     * Deletes an island and stages an optional outbox event atomically in the same transaction.
     *
     * @param id the island id to delete
     * @param outboxEvent optional event to stage atomically in the same transaction
     */
    default void deleteIsland(IslandId id, @Nullable StagedOutboxEvent outboxEvent) {
        deleteIsland(id);
    }

    /**
     * Finds an island whose bounding box contains the specified world coordinates.
     *
     * @param worldName world identifier
     * @param x block X coordinate
     * @param z block Z coordinate
     * @return optional containing the island aggregate if found
     */
    default Optional<Island> findIslandByLocation(String worldName, int x, int z) {
        return Optional.empty();
    }

    /**
     * Loads all active islands located in the specified world.
     *
     * @param worldName world identifier
     * @return list of active islands located in the world
     */
    default java.util.List<Island> findAllByWorld(String worldName) {
        return java.util.Collections.emptyList();
    }
}
