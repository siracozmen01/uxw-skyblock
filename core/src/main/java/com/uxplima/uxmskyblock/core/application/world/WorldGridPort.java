package com.uxplima.uxmskyblock.core.application.world;

import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.world.IslandCoordinates;

/**
 * Hexagonal Outbound Port for world grid operations, coordinate allocation,
 * and island spatial lookups.
 */
public interface WorldGridPort {

    /**
     * Allocates coordinates for a newly created island using the grid sequence index.
     *
     * @param sequenceIndex monotonic sequential island index
     * @return discrete grid center coordinates
     */
    IslandCoordinates allocateCenter(long sequenceIndex);

    /**
     * Calculates default territorial bounds for an island at given center coordinates with a specified radius.
     *
     * @param center center coordinates
     * @param initialRadius initial radius in blocks
     * @return bounded island territory
     */
    IslandBounds createBounds(IslandCoordinates center, int initialRadius);

    /**
     * Resolves the island sequence index corresponding to the given block coordinates, if aligned to the grid.
     *
     * @param x block X coordinate
     * @param z block Z coordinate
     * @return optional sequence index
     */
    Optional<Long> resolveSequenceIndex(int x, int z);
}
