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

    /**
     * The tier every island holds from the start: the first, when it costs nothing, and none otherwise.
     *
     * <p>An operator writes the base as a first tier at no cost, the member cap and the island edge a
     * new island starts with, and those allowances already read an island that bought nothing as
     * standing on it. Sold as a purchase, that tier took nothing, changed nothing, and was listed as the
     * next thing to buy. Only the first: a later free tier is one the operator chose to give away on
     * request, and the allowances do not read it as held.
     */
    public int heldFromStart() {
        return !tiers.isEmpty() && tiers.get(0).costMinorUnits() == 0 ? 1 : 0;
    }

    /** The tier an island is on when it bought {@code bought}: never below the one it starts with. */
    public int effectiveTier(int bought) {
        return Math.max(bought, heldFromStart());
    }

    public Optional<UpgradeTier> getTier(int tierNumber) {
        if (tierNumber <= 0 || tierNumber > tiers.size()) {
            return Optional.empty();
        }
        return Optional.of(tiers.get(tierNumber - 1));
    }
}
