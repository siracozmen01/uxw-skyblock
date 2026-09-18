package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

class TemporaryAccessConfigurationTest {

    @Test
    @DisplayName("Loads default configuration when node is missing")
    void loadsDefaultWhenMissing() {
        CommentedConfigurationNode root = CommentedConfigurationNode.root();
        TemporaryAccessConfiguration config = TemporaryAccessConfiguration.load(root);

        assertThat(config.enabled()).isTrue();
        assertThat(config.defaultDuration()).isEqualTo(Duration.ofHours(1));
        assertThat(config.maxDuration()).isEqualTo(Duration.ofDays(1));
        assertThat(config.purgeInterval()).isEqualTo(Duration.ofMinutes(1));
        assertThat(config.enforceRulesetIsolation()).isTrue();
    }

    @Test
    @DisplayName("Loads custom configuration from HOCON string")
    void loadsCustomHocon() throws IOException {
        String hocon = """
                temporary-access {
                    enabled = false
                    default-duration = "2h"
                    max-duration = "48h"
                    purge-interval = "5m"
                    enforce-ruleset-isolation = false
                }
                """;

        ConfigurationNode root = HoconConfigurationLoader.builder().buildAndLoadString(hocon);
        TemporaryAccessConfiguration config = TemporaryAccessConfiguration.load(root);

        assertThat(config.enabled()).isFalse();
        assertThat(config.defaultDuration()).isEqualTo(Duration.ofHours(2));
        assertThat(config.maxDuration()).isEqualTo(Duration.ofDays(2));
        assertThat(config.purgeInterval()).isEqualTo(Duration.ofMinutes(5));
        assertThat(config.enforceRulesetIsolation()).isFalse();
    }
}
