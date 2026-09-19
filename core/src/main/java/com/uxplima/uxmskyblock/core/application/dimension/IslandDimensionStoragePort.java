package com.uxplima.uxmskyblock.core.application.dimension;

import java.util.Set;

import com.uxplima.uxmskyblock.core.domain.dimension.IslandDimensionType;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * Outbound persistence port for durable tracking of island generated dimension platforms (Sections 2.27 & 2.37).
 */
public interface IslandDimensionStoragePort {

    /**
     * Durably marks that the starter platform for the given dimension has been generated.
     */
    void markDimensionGenerated(IslandId islandId, IslandDimensionType dimension);

    /**
     * Checks if the island has already had its starter platform generated for the given dimension.
     */
    boolean hasGeneratedDimension(IslandId islandId, IslandDimensionType dimension);

    /**
     * Retrieves all generated dimensions for the given island.
     */
    Set<IslandDimensionType> getGeneratedDimensions(IslandId islandId);

    /**
     * Deletes all recorded dimension platform records for an island upon deletion/reset.
     */
    void deleteIslandDimensions(IslandId islandId);
}
