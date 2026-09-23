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

    /**
     * Charges the bank and moves the tier in one transaction, so neither happens without the other.
     *
     * <p>The purchase charged the bank, then moved the tier in a second step. A server that stopped
     * between the two kept the money and gave no tier, and a refund that failed did the same. A store
     * that can do both at once answers here; one that cannot answers empty and the caller takes the
     * two steps itself.
     */
    default java.util.Optional<PaidTierMove> chargeAndMoveTier(TierPurchase purchase) {
        return java.util.Optional.empty();
    }
}
