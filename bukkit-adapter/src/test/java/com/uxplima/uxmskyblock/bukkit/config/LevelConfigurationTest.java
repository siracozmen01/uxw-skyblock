package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

class LevelConfigurationTest {

    @Test
    @DisplayName("Loads default configuration when node is missing or empty")
    void loadsDefaultWhenMissing() {
        CommentedConfigurationNode root = CommentedConfigurationNode.root();
        LevelConfiguration config = LevelConfiguration.load(root);

        assertThat(config.pointsPerLevel()).isEqualTo(100L);
        assertThat(config.bankMinorUnitsPerPoint()).isEqualTo(10_000L);
        assertThat(config.defaultSpawnerWeight()).isEqualTo(25L);
        assertThat(config.questWeight()).isEqualTo(50L);
        assertThat(config.dampingFactor()).isEqualTo(0.85);
        assertThat(config.blockWeights()).containsKey("minecraft:diamond_block");
        assertThat(config.blockWeights().get("minecraft:diamond_block")).isEqualTo(900L);
        assertThat(config.spawnerWeights().get("minecraft:iron_golem")).isEqualTo(5000L);
    }

    @Test
    @DisplayName("Loads custom configuration from HOCON string")
    void loadsCustomHocon() throws IOException {
        String hocon = """
                points-per-level = 200
                bank-minor-units-per-point = 20000
                default-spawner-weight = 30
                quest-weight = 75
                damping-factor = 0.90
                blocks {
                  "minecraft:diamond_block" = 1000
                  "minecraft:gold_block" = 500
                }
                prices {
                  "minecraft:diamond_block" = 100000
                }
                spawners {
                  "minecraft:iron_golem" = 6000
                  "minecraft:blaze" = 1500
                }
                """;

        ConfigurationNode root = HoconConfigurationLoader.builder().buildAndLoadString(hocon);
        LevelConfiguration config = LevelConfiguration.load(root);

        assertThat(config.pointsPerLevel()).isEqualTo(200L);
        assertThat(config.bankMinorUnitsPerPoint()).isEqualTo(20000L);
        assertThat(config.defaultSpawnerWeight()).isEqualTo(30L);
        assertThat(config.questWeight()).isEqualTo(75L);
        assertThat(config.dampingFactor()).isEqualTo(0.90);
        assertThat(config.blockWeights()).containsEntry("minecraft:diamond_block", 1000L);
        assertThat(config.blockWeights()).containsEntry("minecraft:gold_block", 500L);
        assertThat(config.blockPrices()).containsEntry("minecraft:diamond_block", 100000L);
        assertThat(config.spawnerWeights()).containsEntry("minecraft:iron_golem", 6000L);
        assertThat(config.spawnerWeights()).containsEntry("minecraft:blaze", 1500L);
    }
}
