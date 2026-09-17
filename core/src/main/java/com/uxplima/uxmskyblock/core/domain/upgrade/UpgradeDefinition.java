package com.uxplima.uxmskyblock.core.domain.upgrade;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Configuration definition of an upgrade category and its progressive tiers.
 *
 * @param id upgrade category key
 * @param displayName human-readable name
 * @param tiers ordered list of upgrade tiers
 */
public record UpgradeDefinition(UpgradeId id, String displayName, List<UpgradeTier> tiers) {

    public UpgradeDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        tiers = (tiers == null) ? List.of() : List.copyOf(tiers);
    }

    public int maxTier() {
        return tiers.size();
    }

    public Optional<UpgradeTier> getTier(int tierNumber) {
        if (tierNumber <= 0 || tierNumber > tiers.size()) {
            return Optional.empty();
        }
        return Optional.of(tiers.get(tierNumber - 1));
    }
}
