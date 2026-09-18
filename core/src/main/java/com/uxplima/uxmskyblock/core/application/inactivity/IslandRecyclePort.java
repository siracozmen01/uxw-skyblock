package com.uxplima.uxmskyblock.core.application.inactivity;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * Outbound port for performing platform-level coordinate voiding and recycling on a deleted island.
 */
public interface IslandRecyclePort {

    /**
     * Executes chunk voiding and returns the coordinate slot back to the pool for reuse.
     *
     * @param islandId target island ID
     */
    void recycleIsland(IslandId islandId);
}
