package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.Map;
import java.util.Set;

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

    private static HomeConfiguration load(String hocon) throws Exception {
        HoconConfigurationLoader loader = HoconConfigurationLoader.builder()
                .source(() -> new BufferedReader(new StringReader(hocon)))
                .build();
        CommentedConfigurationNode root = loader.load();
        return HomeConfiguration.load(root);
    }
}
