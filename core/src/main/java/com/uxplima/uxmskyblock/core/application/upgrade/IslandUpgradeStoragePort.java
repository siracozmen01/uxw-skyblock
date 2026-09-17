package com.uxplima.uxmskyblock.core.application.upgrade;

import java.util.Map;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;

/**
 * Outbound application port for loading and persisting island upgrade progression.
 */
public interface IslandUpgradeStoragePort {

    /**
     * Retrieves the current tier (level) of a specific upgrade for an island.
     *
     * @param islandId target island ID
     * @param upgradeId target upgrade identifier
     * @return current tier (0 if unpurchased)
     */
    int getUpgradeTier(IslandId islandId, UpgradeId upgradeId);

    /**
     * Retrieves all purchased upgrades and their current tiers for an island.
     *
     * @param islandId target island ID
     * @return map of upgrade identifiers to tiers
     */
    Map<UpgradeId, Integer> getUpgrades(IslandId islandId);

    /**
     * Persists or updates the tier of a specific upgrade for an island.
     *
     * @param islandId target island ID
     * @param upgradeId target upgrade identifier
     * @param tier new tier
     */
    void setUpgradeTier(IslandId islandId, UpgradeId upgradeId, int tier);
}
