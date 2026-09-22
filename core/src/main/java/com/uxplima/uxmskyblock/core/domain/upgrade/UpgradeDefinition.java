package com.uxplima.uxmskyblock.core.domain.upgrade;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;
import org.jspecify.annotations.Nullable;

/**
 * Configuration definition of an upgrade category and its progressive tiers.
 *
 * @param id upgrade category key
 * @param displayName human-readable name
 * @param tiers ordered list of upgrade tiers
 * @param requiredPermission the role permission this upgrade asks for beyond spending the bank,
 *     named by the operator's file, or null when it asks for nothing beyond the money
 */
public record UpgradeDefinition(
        UpgradeId id,
        String displayName,
        List<UpgradeTier> tiers,
        @Nullable IslandPermission requiredPermission) {

    public UpgradeDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        tiers = (tiers == null) ? List.of() : List.copyOf(tiers);
    }

    /** An upgrade that asks for nothing beyond the money it costs. */
    public UpgradeDefinition(UpgradeId id, String displayName, List<UpgradeTier> tiers) {
        this(id, displayName, tiers, null);
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
