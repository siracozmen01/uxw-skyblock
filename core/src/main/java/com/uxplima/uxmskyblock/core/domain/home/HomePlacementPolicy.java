package com.uxplima.uxmskyblock.core.domain.home;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.uxplima.uxmskyblock.core.domain.dimension.DimensionId;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType;

/**
 * Where a player may set a home: which dimensions, whether on their own island only, with which
 * permission, and in which game modes homes are switched off.
 *
 * <p>A home was written wherever the player stood: in any world, on anybody's island. A dimension the
 * operator did not name takes no home, so a world a server adds later is closed until it is opened.
 */
public record HomePlacementPolicy(Map<DimensionId, HomeDimensionRule> dimensions, Set<GameModeType> disabledInModes) {

    /** Why a home may or may not stand where the player is, in the order the reasons are asked. */
    public enum Verdict {
        ALLOWED,
        MODE_DISABLED,
        DIMENSION_NOT_ALLOWED,
        NO_PERMISSION,
        OUTSIDE_ISLAND
    }

    public HomePlacementPolicy {
        Objects.requireNonNull(dimensions, "dimensions must not be null");
        Objects.requireNonNull(disabledInModes, "disabledInModes must not be null");
        dimensions = Map.copyOf(new LinkedHashMap<>(dimensions));
        disabledInModes = disabledInModes.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(disabledInModes));
    }

    /** Homes in the three vanilla dimensions, on the player's own island, for everyone, in every mode. */
    public static HomePlacementPolicy shipped() {
        return new HomePlacementPolicy(
                Map.of(
                        DimensionId.OVERWORLD, HomeDimensionRule.onIsland(),
                        DimensionId.THE_NETHER, HomeDimensionRule.onIsland(),
                        DimensionId.THE_END, HomeDimensionRule.onIsland()),
                Set.of());
    }

    /**
     * The permission node a home in this dimension takes, if one. Asked first, on the player's own
     * thread, where a permission may be read.
     */
    public Optional<String> permissionFor(DimensionId dimension) {
        Objects.requireNonNull(dimension, "dimension must not be null");
        HomeDimensionRule rule = dimensions.get(dimension);
        return rule == null || rule.permission().isEmpty() ? Optional.empty() : Optional.of(rule.permission());
    }

    /**
     * Whether a home may stand here.
     *
     * @param hasPermission whether the player holds {@link #permissionFor} the dimension, true when it takes none
     * @param onOwnIsland whether the spot lies within the player's own island's bounds
     */
    public Verdict check(DimensionId dimension, GameModeType mode, boolean hasPermission, boolean onOwnIsland) {
        Objects.requireNonNull(dimension, "dimension must not be null");
        Objects.requireNonNull(mode, "mode must not be null");
        if (disabledInModes.contains(mode)) {
            return Verdict.MODE_DISABLED;
        }
        HomeDimensionRule rule = dimensions.get(dimension);
        if (rule == null || !rule.allowed()) {
            return Verdict.DIMENSION_NOT_ALLOWED;
        }
        if (!rule.permission().isEmpty() && !hasPermission) {
            return Verdict.NO_PERMISSION;
        }
        if (rule.onIslandOnly() && !onOwnIsland) {
            return Verdict.OUTSIDE_ISLAND;
        }
        return Verdict.ALLOWED;
    }
}
