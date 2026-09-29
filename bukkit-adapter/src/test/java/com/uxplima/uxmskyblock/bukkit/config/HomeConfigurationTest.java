package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.Map;
import java.util.Set;

import com.uxplima.uxmskyblock.core.domain.dimension.DimensionId;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType;
import com.uxplima.uxmskyblock.core.domain.home.HomePlacementPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

class HomeConfigurationTest {

    @Test
    @DisplayName("The shipped block gives everybody one home")
    void shippedBlockGivesOne() throws Exception {
        HomeConfiguration config = load("""
                homes {
                    enabled = true
                    base = 1
                    max = 10
                    permission-tiers {
                    }
                }
                """);

        assertThat(config.enabled()).isTrue();
        assertThat(config.allowanceFor(node -> false)).isEqualTo(1);
    }

    @Test
    @DisplayName("The highest node a player holds decides the allowance")
    void highestNodeWins() throws Exception {
        HomeConfiguration config = load("""
                homes {
                    base = 1
                    max = 10
                    permission-tiers {
                        "server.homes.three" = 3
                        "server.homes.five" = 5
                    }
                }
                """);

        Set<String> held = Set.of("server.homes.three", "server.homes.five");

        assertThat(config.allowanceFor(held::contains)).isEqualTo(5);
        assertThat(config.allowanceFor(node -> node.equals("server.homes.three")))
                .isEqualTo(3);
        assertThat(config.allowanceFor(node -> false)).isEqualTo(1);
    }

    @Test
    @DisplayName("No node can raise a player above the ceiling")
    void theCeilingHolds() throws Exception {
        HomeConfiguration config = load("""
                homes {
                    base = 1
                    max = 4
                    permission-tiers {
                        "server.homes.many" = 99
                    }
                }
                """);

        assertThat(config.allowanceFor(node -> true)).isEqualTo(4);
    }

    @Test
    @DisplayName("A ceiling below the base is refused rather than quietly swapped")
    void aCeilingBelowTheBaseIsRefused() {
        assertThatThrownBy(() -> new HomeConfiguration(true, 5, 2, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("homes.max");
    }

    @Test
    @DisplayName("A file with no homes block keeps the shipped answer")
    void missingBlockFallsBack() throws Exception {
        assertThat(HomeConfiguration.load(null)).isEqualTo(HomeConfiguration.defaults());
        assertThat(load("server-node { id = \"node-1\" }\n").baseHomes()).isEqualTo(1);
    }

    @Test
    @DisplayName("The dimensions block says where a home may stand, and a mode may switch homes off")
    void theDimensionsBlockIsRead() throws Exception {
        HomeConfiguration config = load("""
                homes {
                    dimensions {
                        overworld { allowed = true, on-island-only = false }
                        the_nether { allowed = true, on-island-only = true, permission = "server.homes.nether" }
                        the_end { allowed = false }
                    }
                    disabled-in-modes = [ "oneblock", "no-such-mode" ]
                }
                """);

        HomePlacementPolicy placement = config.placement();

        assertThat(placement.check(DimensionId.OVERWORLD, GameModeType.SKYBLOCK, true, false))
                .isEqualTo(HomePlacementPolicy.Verdict.ALLOWED);
        assertThat(placement.permissionFor(DimensionId.THE_NETHER)).contains("server.homes.nether");
        assertThat(placement.check(DimensionId.THE_NETHER, GameModeType.SKYBLOCK, true, false))
                .isEqualTo(HomePlacementPolicy.Verdict.OUTSIDE_ISLAND);
        assertThat(placement.check(DimensionId.THE_END, GameModeType.SKYBLOCK, true, true))
                .isEqualTo(HomePlacementPolicy.Verdict.DIMENSION_NOT_ALLOWED);
        assertThat(placement.disabledInModes()).containsExactly(GameModeType.ONEBLOCK);
    }

    @Test
    @DisplayName("Without a dimensions block the shipped rules stand, and a mode is still switched off")
    void noDimensionsBlockKeepsTheShippedRules() throws Exception {
        HomeConfiguration config = load("""
                homes {
                    disabled-in-modes = [ "oneblock" ]
                }
                """);

        assertThat(config.placement().dimensions())
                .isEqualTo(HomePlacementPolicy.shipped().dimensions());
        assertThat(config.placement().check(DimensionId.OVERWORLD, GameModeType.ONEBLOCK, true, true))
                .isEqualTo(HomePlacementPolicy.Verdict.MODE_DISABLED);
    }

    private static HomeConfiguration load(String hocon) throws Exception {
        HoconConfigurationLoader loader = HoconConfigurationLoader.builder()
                .source(() -> new BufferedReader(new StringReader(hocon)))
                .build();
        CommentedConfigurationNode root = loader.load();
        return HomeConfiguration.load(root);
    }
}
