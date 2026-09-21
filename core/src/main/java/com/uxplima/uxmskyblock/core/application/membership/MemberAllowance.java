package com.uxplima.uxmskyblock.core.application.membership;

import java.util.Objects;
import java.util.function.ToIntFunction;

import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeTier;

/**
 * How many members an island may hold, read off the upgrade the operator wrote.
 *
 * <p>The number is not in the code. It is the {@code max_members} property of whichever tier of the
 * member limit upgrade the island has bought, and every one of those tiers is a line in
 * {@code upgrades.conf}. An island that has bought none is on the first tier, which is the base an
 * operator sets by editing that line rather than by asking for a new release.
 */
public final class MemberAllowance implements ToIntFunction<IslandId> {

    /** The upgrade whose tiers carry the cap. */
    public static final UpgradeId MEMBER_LIMIT = UpgradeId.of("member_limit");

    /** The property on a tier that says how many members it allows. */
    public static final String MAX_MEMBERS = "max_members";

    private final IslandUpgradeService upgradeService;

    public MemberAllowance(IslandUpgradeService upgradeService) {
        this.upgradeService = Objects.requireNonNull(upgradeService, "upgradeService must not be null");
    }

    @Override
    public int applyAsInt(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        int tier = Math.max(1, upgradeService.getCurrentTier(islandId, MEMBER_LIMIT));
        return upgradeService
                .getDefinition(MEMBER_LIMIT)
                .flatMap(definition -> definition.tiers().stream()
                        .filter(candidate -> candidate.tier() == tier)
                        .findFirst())
                .map(MemberAllowance::membersOf)
                // An operator who deletes the upgrade from their file has an island that holds its
                // owner and nobody else. That is a working island, and it is what their file says.
                .orElse(1);
    }

    private static int membersOf(UpgradeTier tier) {
        Double allowed = tier.properties().get(MAX_MEMBERS);
        return allowed == null ? 1 : Math.max(1, (int) Math.round(allowed));
    }
}
