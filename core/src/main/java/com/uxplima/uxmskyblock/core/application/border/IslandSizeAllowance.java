package com.uxplima.uxmskyblock.core.application.border;

import java.util.Objects;
import java.util.Optional;
import java.util.function.ToIntFunction;

import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeTier;

/**
 * How far an island reaches from its centre, read off the upgrade the operator wrote.
 *
 * <p>The number is not in the code. It is the {@code radius} property of whichever tier of the
 * island size upgrade the island has bought, and every one of those tiers is a line in
 * {@code upgrades.conf}. An island that has bought none is on the first tier, so the size a new
 * island starts at is a line in that file too, rather than a constant a release has to change.
 *
 * <p>This is the same shape as the member allowance, for the same reason: one upgrade, one property,
 * one place an operator edits it.
 */
public final class IslandSizeAllowance implements ToIntFunction<IslandId> {

    /** The upgrade whose tiers carry the radius. */
    public static final UpgradeId ISLAND_SIZE = UpgradeId.SIZE;

    /** The property on a tier that says how far the island reaches. */
    public static final String RADIUS = "radius";

    private final IslandUpgradeService upgradeService;

    public IslandSizeAllowance(IslandUpgradeService upgradeService) {
        this.upgradeService = Objects.requireNonNull(upgradeService, "upgradeService must not be null");
    }

    @Override
    public int applyAsInt(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        return radiusOfTier(Math.max(1, upgradeService.getCurrentTier(islandId, ISLAND_SIZE)));
    }

    /** What an island that has bought nothing reaches, which is the first tier of the upgrade. */
    public int baseRadius() {
        return radiusOfTier(1);
    }

    private int radiusOfTier(int tier) {
        return upgradeService
                .getDefinition(ISLAND_SIZE)
                .flatMap(definition -> definition.getTier(tier))
                .map(IslandSizeAllowance::radiusOf)
                // An operator who deletes the upgrade from their file has islands one block across,
                // which is a working island and is what their file says. Nothing here invents a size.
                .orElse(1);
    }

    private static int radiusOf(UpgradeTier tier) {
        Double radius = tier.properties().get(RADIUS);
        return radius == null ? 1 : Math.max(1, (int) Math.round(radius));
    }

    /** The tier an island is on, for a caller that wants to say so rather than act on it. */
    public Optional<UpgradeTier> tierOf(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        int tier = Math.max(1, upgradeService.getCurrentTier(islandId, ISLAND_SIZE));
        return upgradeService.getDefinition(ISLAND_SIZE).flatMap(definition -> definition.getTier(tier));
    }
}
