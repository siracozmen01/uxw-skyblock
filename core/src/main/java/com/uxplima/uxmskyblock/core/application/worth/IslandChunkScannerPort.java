package com.uxplima.uxmskyblock.core.application.worth;

import java.util.Map;
import java.util.function.BiConsumer;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;

/**
 * Outbound port for performing region-aware chunk block and spawner counting across an island's bounds.
 */
public interface IslandChunkScannerPort {

    /**
     * Asynchronously scans all chunks intersecting the given island bounds, aggregating block and spawner counts.
     *
     * @param islandId target island ID
     * @param worldName world containing the island
     * @param bounds spatial bounds of the island
     * @param callback consumer invoked upon completion with (blockCounts, spawnerCounts)
     */
    void scanIsland(
            IslandId islandId,
            String worldName,
            IslandBounds bounds,
            BiConsumer<Map<String, Integer>, Map<String, Integer>> callback);
}
