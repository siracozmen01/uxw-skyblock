package com.uxplima.uxmskyblock.core.application.freeze;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * Outbound port for immediately evicting non-staff visitors from an island when administrative quarantine is activated.
 */
public interface IslandVisitorEvictionPort {

    /**
     * Teleports all non-staff visitors currently within the specified island's boundary to spawn.
     *
     * @param islandId target island
     * @param reason freeze rationale to display to evicted visitors
     */
    void evictNonStaffVisitors(IslandId islandId, String reason);
}
