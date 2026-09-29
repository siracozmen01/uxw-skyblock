package com.uxplima.uxmskyblock.core.domain.home;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;

import com.uxplima.uxmskyblock.core.domain.dimension.DimensionId;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType;
import com.uxplima.uxmskyblock.core.domain.home.HomePlacementPolicy.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A home is checked against the dimension's rule, the player's permission and the game mode before it
 * is written.
 *
 * <p>The game mode architecture names this test. A home was written wherever the player stood, on
 * anybody's island and in any world, and no mode could switch homes off.
 */
class MultiHomeDimensionPolicyTest {

    private static final DimensionId MINING_REALM = DimensionId.of("myserver:mining_realm");

    private final HomePlacementPolicy policy = new HomePlacementPolicy(
            Map.of(
                    DimensionId.OVERWORLD,
                    HomeDimensionRule.onIsland(),
                    DimensionId.THE_NETHER,
                    new HomeDimensionRule(true, true, "server.homes.nether"),
                    DimensionId.THE_END,
                    new HomeDimensionRule(false, true, ""),
                    MINING_REALM,
                    new HomeDimensionRule(true, false, "")),
            Set.of(GameModeType.ONEBLOCK));

    @Test
    @DisplayName("A home on the player's own island in the overworld is allowed, and off it is refused")
    void theOverworldTakesHomesOnTheIsland() {
        assertThat(policy.check(DimensionId.OVERWORLD, GameModeType.SKYBLOCK, true, true))
                .isEqualTo(Verdict.ALLOWED);
        assertThat(policy.check(DimensionId.OVERWORLD, GameModeType.SKYBLOCK, true, false))
                .isEqualTo(Verdict.OUTSIDE_ISLAND);
    }

    @Test
    @DisplayName("A dimension that takes a permission refuses a player without it, before the island is asked")
    void aDimensionPermissionIsAsked() {
        assertThat(policy.permissionFor(DimensionId.THE_NETHER)).contains("server.homes.nether");
        assertThat(policy.permissionFor(DimensionId.OVERWORLD)).isEmpty();
        assertThat(policy.check(DimensionId.THE_NETHER, GameModeType.SKYBLOCK, false, false))
                .isEqualTo(Verdict.NO_PERMISSION);
        assertThat(policy.check(DimensionId.THE_NETHER, GameModeType.SKYBLOCK, true, true))
                .isEqualTo(Verdict.ALLOWED);
    }

    @Test
    @DisplayName("A dimension switched off, or never named, takes no home at all")
    void aClosedOrUnnamedDimensionTakesNoHome() {
        assertThat(policy.check(DimensionId.THE_END, GameModeType.SKYBLOCK, true, true))
                .isEqualTo(Verdict.DIMENSION_NOT_ALLOWED);
        assertThat(policy.check(DimensionId.of("myserver:sky_realm"), GameModeType.SKYBLOCK, true, true))
                .isEqualTo(Verdict.DIMENSION_NOT_ALLOWED);
    }

    @Test
    @DisplayName("A server's own dimension may take homes anywhere in it")
    void aCustomDimensionMayTakeHomesAnywhere() {
        assertThat(policy.check(MINING_REALM, GameModeType.SKYBLOCK, true, false))
                .isEqualTo(Verdict.ALLOWED);
    }

    @Test
    @DisplayName("A game mode with homes switched off refuses every home, whatever else holds")
    void aModeCanSwitchHomesOff() {
        assertThat(policy.check(DimensionId.OVERWORLD, GameModeType.ONEBLOCK, true, true))
                .isEqualTo(Verdict.MODE_DISABLED);
    }

    @Test
    @DisplayName("The shipped policy allows homes in the three vanilla dimensions, on the island, for everyone")
    void theShippedPolicy() {
        HomePlacementPolicy shipped = HomePlacementPolicy.shipped();

        for (DimensionId dimension :
                new DimensionId[] {DimensionId.OVERWORLD, DimensionId.THE_NETHER, DimensionId.THE_END}) {
            for (GameModeType mode : GameModeType.values()) {
                assertThat(shipped.check(dimension, mode, false, true)).isEqualTo(Verdict.ALLOWED);
                assertThat(shipped.check(dimension, mode, false, false)).isEqualTo(Verdict.OUTSIDE_ISLAND);
            }
        }
        assertThat(shipped.check(MINING_REALM, GameModeType.SKYBLOCK, true, true))
                .isEqualTo(Verdict.DIMENSION_NOT_ALLOWED);
    }
}
