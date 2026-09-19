package com.uxplima.uxmskyblock.core.application.recycle;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;

/**
 * Outbound port for performing platform-native Folia asynchronous chunk voiding.
 */
public interface IslandVoidingPort {

    /**
     * Schedules asynchronous Folia chunk tasks to evacuate players, kill non-player entities,
     * purge tile entities, and zero out all chunks spanning the island bounds.
     *
     * @param islandId target island ID
     * @param worldName target world identifier
     * @param bounds spatial bounds of the island
     */
    void voidIslandChunks(IslandId islandId, String worldName, IslandBounds bounds);
}
