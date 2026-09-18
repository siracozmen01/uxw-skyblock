package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

class WarpConfigurationTest {

    @Test
    @DisplayName("Loads default configuration when node is missing")
    void loadsDefaultWhenMissing() {
        CommentedConfigurationNode root = CommentedConfigurationNode.root();
        WarpConfiguration config = WarpConfiguration.load(root);

        assertThat(config.enabled()).isTrue();
        assertThat(config.baseWarpLimit()).isEqualTo(2);
        assertThat(config.searchRadius()).isEqualTo(5);
        assertThat(config.warmupDuration()).isEqualTo(Duration.ofSeconds(3));
        assertThat(config.cancelOnMove()).isTrue();
        assertThat(config.cooldownDuration()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("Loads custom configuration from HOCON string")
    void loadsCustomHocon() throws IOException {
        String hocon = """
                warps {
                    enabled = false
                    base-warp-limit = 5
                    search-radius = 8
                    warmup-seconds = 10
                    cancel-on-move = false
                    cooldown-seconds = 15
                }
                """;

        ConfigurationNode root = HoconConfigurationLoader.builder().buildAndLoadString(hocon);
        WarpConfiguration config = WarpConfiguration.load(root);

        assertThat(config.enabled()).isFalse();
        assertThat(config.baseWarpLimit()).isEqualTo(5);
        assertThat(config.searchRadius()).isEqualTo(8);
        assertThat(config.warmupDuration()).isEqualTo(Duration.ofSeconds(10));
        assertThat(config.cancelOnMove()).isFalse();
        assertThat(config.cooldownDuration()).isEqualTo(Duration.ofSeconds(15));
    }
}
