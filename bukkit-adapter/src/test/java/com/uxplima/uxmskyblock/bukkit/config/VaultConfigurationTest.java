package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

class VaultConfigurationTest {

    @Test
    @DisplayName("Loads default configuration when node is missing")
    void loadsDefaultWhenMissing() {
        CommentedConfigurationNode root = CommentedConfigurationNode.root();
        VaultConfiguration config = VaultConfiguration.load(root);

        assertThat(config.enabled()).isTrue();
        assertThat(config.basePages()).isEqualTo(1);
        assertThat(config.maxPages()).isEqualTo(10);
        assertThat(config.slotsPerPage()).isEqualTo(54);
        assertThat(config.leaseDuration()).isEqualTo(Duration.ofSeconds(60));
        assertThat(config.auditLogLimit()).isEqualTo(50);
    }

    @Test
    @DisplayName("Loads custom configuration from HOCON string")
    void loadsCustomHocon() throws IOException {
        String hocon = """
                vault {
                    enabled = false
                    base-pages = 2
                    max-pages = 8
                    slots-per-page = 27
                    lease-duration-seconds = 45
                    audit-log-limit = 100
                }
                """;

        ConfigurationNode root = HoconConfigurationLoader.builder().buildAndLoadString(hocon);
        VaultConfiguration config = VaultConfiguration.load(root);

        assertThat(config.enabled()).isFalse();
        assertThat(config.basePages()).isEqualTo(2);
        assertThat(config.maxPages()).isEqualTo(8);
        assertThat(config.slotsPerPage()).isEqualTo(27);
        assertThat(config.leaseDuration()).isEqualTo(Duration.ofSeconds(45));
        assertThat(config.auditLogLimit()).isEqualTo(100);
    }
}
