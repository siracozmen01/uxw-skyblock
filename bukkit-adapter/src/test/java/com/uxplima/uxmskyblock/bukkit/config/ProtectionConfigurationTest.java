package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

class ProtectionConfigurationTest {

    @Test
    @DisplayName("Loads default protection config when node is empty")
    void loadsDefaultWhenEmpty() {
        CommentedConfigurationNode root = CommentedConfigurationNode.root();
        ProtectionConfiguration config = ProtectionConfiguration.load(root);

        assertThat(config.obsidianRecoveryEnabled()).isTrue();
        assertThat(config.obsidianRecoveryAccidentalOnly()).isTrue();
        assertThat(config.obsidianRecoveryExpiration()).isEqualTo(Duration.ofSeconds(60));
        assertThat(config.voidRecoveryEnabled()).isTrue();
        assertThat(config.voidRecoveryThresholdY()).isEqualTo(-64);
        assertThat(config.voidRecoveryFallDamageShield()).isEqualTo(Duration.ofSeconds(10));
        assertThat(config.kineticWardEnabled()).isTrue();
        assertThat(config.kineticWardRadius()).isEqualTo(5.0);
        assertThat(config.kineticWardForce()).isEqualTo(1.5);
        assertThat(config.kineticWardVerticalLift()).isEqualTo(0.35);
    }

    @Test
    @DisplayName("Loads custom protection config from HOCON")
    void loadsCustomHocon() throws IOException {
        String hocon = """
                obsidian-recovery {
                    enabled = false
                    accidental-only = false
                    expiration-seconds = 120
                }
                void-recovery {
                    enabled = false
                    threshold-y = -100
                    fall-damage-shield-seconds = 5
                }
                kinetic-ward {
                    enabled = false
                    radius = 8.5
                    repulsion-force = 2.0
                    vertical-lift = 0.5
                }
                """;

        ConfigurationNode root = HoconConfigurationLoader.builder().buildAndLoadString(hocon);
        ProtectionConfiguration config = ProtectionConfiguration.load(root);

        assertThat(config.obsidianRecoveryEnabled()).isFalse();
        assertThat(config.obsidianRecoveryAccidentalOnly()).isFalse();
        assertThat(config.obsidianRecoveryExpiration()).isEqualTo(Duration.ofMinutes(2));
        assertThat(config.voidRecoveryEnabled()).isFalse();
        assertThat(config.voidRecoveryThresholdY()).isEqualTo(-100);
        assertThat(config.voidRecoveryFallDamageShield()).isEqualTo(Duration.ofSeconds(5));
        assertThat(config.kineticWardEnabled()).isFalse();
        assertThat(config.kineticWardRadius()).isEqualTo(8.5);
        assertThat(config.kineticWardForce()).isEqualTo(2.0);
        assertThat(config.kineticWardVerticalLift()).isEqualTo(0.5);
    }
}
