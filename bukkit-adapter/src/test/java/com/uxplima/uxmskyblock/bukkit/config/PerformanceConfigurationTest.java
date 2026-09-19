package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

class PerformanceConfigurationTest {

    @Test
    @DisplayName("Loads default performance config when node is empty")
    void loadsDefaultWhenEmpty() {
        CommentedConfigurationNode root = CommentedConfigurationNode.root();
        PerformanceConfiguration config = PerformanceConfiguration.load(root);

        assertThat(config.adaptiveThrottle()).isTrue();
        assertThat(config.tpsThreshold()).isEqualTo(19.5);
        assertThat(config.normalBlocksPerTick()).isEqualTo(128);
        assertThat(config.throttledBlocksPerTick()).isEqualTo(16);
        assertThat(config.normalChunksPerSec()).isEqualTo(100);
        assertThat(config.throttledChunksPerSec()).isEqualTo(20);
    }

    @Test
    @DisplayName("Loads custom performance config from HOCON")
    void loadsCustomHocon() throws IOException {
        String hocon = """
                adaptive-throttle = false
                tps-threshold = 18.0
                normal-blocks-per-tick = 256
                throttled-blocks-per-tick = 32
                normal-deletion-chunks-per-sec = 200
                throttled-deletion-chunks-per-sec = 40
                """;

        ConfigurationNode root = HoconConfigurationLoader.builder().buildAndLoadString(hocon);
        PerformanceConfiguration config = PerformanceConfiguration.load(root);

        assertThat(config.adaptiveThrottle()).isFalse();
        assertThat(config.tpsThreshold()).isEqualTo(18.0);
        assertThat(config.normalBlocksPerTick()).isEqualTo(256);
        assertThat(config.throttledBlocksPerTick()).isEqualTo(32);
        assertThat(config.normalChunksPerSec()).isEqualTo(200);
        assertThat(config.throttledChunksPerSec()).isEqualTo(40);
    }
}
