package com.uxplima.uxmskyblock.core.domain.limit;

import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;
import org.jspecify.annotations.Nullable;

/**
 * Immutable quota definition for a specific limit type (Section 2.31).
 *
 * @param baseLimit initial quota available to un-upgraded islands
 * @param upgradeId optional upgrade identifier that dynamically increases the limit
 * @param perTierBonus additional quota added per tier of the upgrade unlocked
 */
public record LimitQuota(int baseLimit, @Nullable UpgradeId upgradeId, int perTierBonus) {

    public LimitQuota {
        if (baseLimit < 0) {
            throw new IllegalArgumentException("baseLimit must not be negative: " + baseLimit);
        }
        if (perTierBonus < 0) {
            throw new IllegalArgumentException("perTierBonus must not be negative: " + perTierBonus);
        }
    }
}
