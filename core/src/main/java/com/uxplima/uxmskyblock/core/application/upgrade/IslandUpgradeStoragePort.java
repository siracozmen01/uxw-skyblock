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

    /**
     * Moves an upgrade from {@code expectedTier} to {@code newTier}, and only from there.
     *
     * <p>A purchase reads the tier, charges the bank and then writes the next one. Two purchases for
     * the same island can read the same tier and both pay for it, and an unconditional write makes
     * that look like one purchase. This refuses the second, so the caller can give the money back.
     *
     * @return true when this call moved the tier, false when somebody else moved it first
     */
    boolean compareAndSetUpgradeTier(IslandId islandId, UpgradeId upgradeId, int expectedTier, int newTier);
}
