package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.bukkit.Material;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

class GeneratorsConfigurationTest extends com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness {

    @Test
    @DisplayName("Default configuration contains tiers 0 through 4 with valid materials")
    void defaultConfigurationContainsAllTiers() {
        GeneratorsConfiguration config = GeneratorsConfiguration.defaultConfiguration();

        assertThat(config.enabled()).isTrue();
        assertThat(config.tierRates()).containsKey(0);
        assertThat(config.tierRates()).containsKey(1);
        assertThat(config.tierRates()).containsKey(2);
        assertThat(config.tierRates()).containsKey(3);
        assertThat(config.tierRates()).containsKey(4);

        // Tier 0 should only roll cobblestone
        assertThat(config.roll(0, 0.5)).isEqualTo(Material.COBBLESTONE);

        // Tier 4 roll test
        Material rolled = config.roll(4, 0.0);
        assertThat(rolled).isNotNull();
    }

    @Test
    @DisplayName("Roll selects exact material based on cumulative weight and random roll")
    void rollSelectsExactMaterial() {
        Map<Material, Double> rates = Map.of(
                Material.COBBLESTONE, 0.5,
                Material.DIAMOND_ORE, 0.5);
        GeneratorsConfiguration config = new GeneratorsConfiguration(true, Map.of(1, rates));

        // When roll is in first half (e.g. 0.2), COBBLESTONE is returned
        // When roll is in second half (e.g. 0.8), DIAMOND_ORE is returned
        Material first = config.roll(1, 0.1);
        Material second = config.roll(1, 0.9);

        assertThat(first).isNotEqualTo(second);
        assertThat(first).isIn(Material.COBBLESTONE, Material.DIAMOND_ORE);
        assertThat(second).isIn(Material.COBBLESTONE, Material.DIAMOND_ORE);
    }

    @Test
    @DisplayName("Loads custom generators configuration from HOCON node")
    void loadsCustomConfigurationFromHocon() throws Exception {
        String hocon = """
                enabled = true
                tiers {
                    "0" {
                        rates {
                            COBBLESTONE = 1.0
                        }
                    }
                    "1" {
                        rates {
                            COBBLESTONE = 0.8
                            IRON_ORE = 0.2
                        }
                    }
                }
                """;

        ConfigurationNode node = HoconConfigurationLoader.builder().buildAndLoadString(hocon);
        GeneratorsConfiguration config = GeneratorsConfiguration.load(node);

        assertThat(config.enabled()).isTrue();
        assertThat(config.tierRates()).containsKey(0);
        assertThat(config.tierRates()).containsKey(1);
        assertThat(config.tierRates().get(1)).containsEntry(Material.COBBLESTONE, 0.8);
        assertThat(config.tierRates().get(1)).containsEntry(Material.IRON_ORE, 0.2);
    }

    @Test
    @DisplayName("A tier the file no longer writes keeps the best tier still written below it")
    void anUnwrittenTierKeepsTheBestTierBelow() {
        GeneratorsConfiguration config = new GeneratorsConfiguration(
                true,
                Map.of(
                        0, Map.of(Material.COBBLESTONE, 1.0),
                        2, Map.of(Material.IRON_ORE, 1.0),
                        3, Map.of(Material.DIAMOND_ORE, 1.0)));

        assertThat(config.roll(5, 0.5))
                .describedAs("an island that bought tier five after the operator cut the file to three")
                .isEqualTo(Material.DIAMOND_ORE);
        assertThat(config.roll(1, 0.5))
                .describedAs("a gap in the file falls to the tier below it, not above")
                .isEqualTo(Material.COBBLESTONE);
    }

    @Test
    @DisplayName("A tier below every tier the file writes takes the lowest one written")
    void aTierBelowEveryWrittenTierTakesTheLowest() {
        GeneratorsConfiguration config = new GeneratorsConfiguration(true, Map.of(2, Map.of(Material.IRON_ORE, 1.0)));

        assertThat(config.roll(1, 0.5)).isEqualTo(Material.IRON_ORE);
    }
}
