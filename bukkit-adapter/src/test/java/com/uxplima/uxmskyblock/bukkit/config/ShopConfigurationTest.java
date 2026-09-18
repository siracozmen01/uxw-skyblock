package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

class ShopConfigurationTest {

    @Test
    @DisplayName("Loads default configuration when node is missing")
    void loadsDefaultWhenMissing() {
        CommentedConfigurationNode root = CommentedConfigurationNode.root();
        ShopConfiguration config = ShopConfiguration.load(root);

        assertThat(config.enabled()).isTrue();
        assertThat(config.dampingFactor()).isEqualTo(0.85);
        assertThat(config.asyncRefreshInterval()).isEqualTo(Duration.ofMinutes(2));
        assertThat(config.defaultStockBaseline()).isEqualTo(100L);
        assertThat(config.defaultElasticity()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("Loads custom configuration from HOCON string")
    void loadsCustomHocon() throws IOException {
        String hocon = """
                shop {
                    enabled = false
                    damping-factor = 0.70
                    async-refresh-interval = "5m"
                    default-stock-baseline = 250
                    default-elasticity = 0.8
                }
                """;

        ConfigurationNode root = HoconConfigurationLoader.builder().buildAndLoadString(hocon);
        ShopConfiguration config = ShopConfiguration.load(root);

        assertThat(config.enabled()).isFalse();
        assertThat(config.dampingFactor()).isEqualTo(0.70);
        assertThat(config.asyncRefreshInterval()).isEqualTo(Duration.ofMinutes(5));
        assertThat(config.defaultStockBaseline()).isEqualTo(250L);
        assertThat(config.defaultElasticity()).isEqualTo(0.8);
    }
}
