package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

class AntiAbuseConfigurationTest {

    @Test
    @DisplayName("Default configuration has canonical defaults")
    void defaultConfigurationHasCanonicalDefaults() {
        AntiAbuseConfiguration config = AntiAbuseConfiguration.defaultConfiguration();

        assertThat(config.purgeInventoryOnReset()).isTrue();
        assertThat(config.quarantineDuration()).isEqualTo(Duration.ofMinutes(15));
        assertThat(config.resetCooldown()).isEqualTo(Duration.ofHours(12));
        assertThat(config.maxResetsPerDay()).isEqualTo(3);
        assertThat(config.resetWindowDuration()).isEqualTo(Duration.ofHours(24));
        assertThat(config.coopJoinCooldown()).isEqualTo(Duration.ofHours(24));
        assertThat(config.resetBypassPermission()).isEqualTo("uxmskyblock.bypass.resetlimits");
        assertThat(config.coopBypassPermission()).isEqualTo("uxmskyblock.bypass.coopcooldown");
        assertThat(config.quarantineBypassPermission()).isEqualTo("uxmskyblock.bypass.quarantine");
    }

    @Test
    @DisplayName("Loads custom configuration from HOCON")
    void loadsCustomConfigurationFromHocon() throws Exception {
        String hocon = """
                anti-abuse {
                    purge-inventory-on-reset = false
                    quarantine-duration = "30m"
                    reset-cooldown = "6h"
                    max-resets-per-day = 5
                    reset-window-duration = "12h"
                    coop-join-cooldown = "48h"
                    reset-bypass-permission = "custom.bypass.reset"
                    coop-bypass-permission = "custom.bypass.coop"
                    quarantine-bypass-permission = "custom.bypass.quarantine"
                }
                """;

        ConfigurationNode node = HoconConfigurationLoader.builder().buildAndLoadString(hocon);

        AntiAbuseConfiguration config = AntiAbuseConfiguration.load(node);

        assertThat(config.purgeInventoryOnReset()).isFalse();
        assertThat(config.quarantineDuration()).isEqualTo(Duration.ofMinutes(30));
        assertThat(config.resetCooldown()).isEqualTo(Duration.ofHours(6));
        assertThat(config.maxResetsPerDay()).isEqualTo(5);
        assertThat(config.resetWindowDuration()).isEqualTo(Duration.ofHours(12));
        assertThat(config.coopJoinCooldown()).isEqualTo(Duration.ofDays(2));
        assertThat(config.resetBypassPermission()).isEqualTo("custom.bypass.reset");
        assertThat(config.coopBypassPermission()).isEqualTo("custom.bypass.coop");
        assertThat(config.quarantineBypassPermission()).isEqualTo("custom.bypass.quarantine");
    }
}
