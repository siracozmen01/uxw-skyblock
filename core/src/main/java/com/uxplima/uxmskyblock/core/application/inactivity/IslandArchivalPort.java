package com.uxplima.uxmskyblock.core.application.inactivity;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * Outbound port for performing platform-level archival actions on an abandoned island.
 */
public interface IslandArchivalPort {

    /**
     * Marks an island as dormant, unloads from active memory cache, and locks warp points.
     *
     * @param islandId target island ID
     */
    void archiveIsland(IslandId islandId);
}
