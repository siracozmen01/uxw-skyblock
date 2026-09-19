package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

class BankConfigurationTest {

    @Test
    @DisplayName("Loads default configuration when node is missing")
    void loadsDefaultWhenMissing() {
        CommentedConfigurationNode root = CommentedConfigurationNode.root();
        BankConfiguration config = BankConfiguration.load(root);

        assertThat(config.upkeepPolicy().enabled()).isFalse();
        assertThat(config.upkeepPolicy().interval()).isEqualTo(Duration.ofHours(24));
        assertThat(config.upkeepPolicy().baseFeeMinorUnits()).isEqualTo(50_000L);
        assertThat(config.upkeepPolicy().perMemberFeeMinorUnits()).isEqualTo(10_000L);
        assertThat(config.upkeepPolicy().graceDuration()).isEqualTo(Duration.ofDays(3));
        assertThat(config.upkeepPolicy().autoRemediateOnDeposit()).isTrue();
    }

    @Test
    @DisplayName("Loads custom configuration from HOCON string")
    void loadsCustomHocon() throws IOException {
        String hocon = """
                bank {
                    upkeep {
                        enabled = true
                        interval = "12h"
                        base-fee = 750.00
                        per-member-fee = 150.00
                        grace-duration = "48h"
                        auto-remediate-on-deposit = false
                    }
                }
                """;

        ConfigurationNode root = HoconConfigurationLoader.builder().buildAndLoadString(hocon);
        BankConfiguration config = BankConfiguration.load(root);

        assertThat(config.upkeepPolicy().enabled()).isTrue();
        assertThat(config.upkeepPolicy().interval()).isEqualTo(Duration.ofHours(12));
        assertThat(config.upkeepPolicy().baseFeeMinorUnits()).isEqualTo(75_000L);
        assertThat(config.upkeepPolicy().perMemberFeeMinorUnits()).isEqualTo(15_000L);
        assertThat(config.upkeepPolicy().graceDuration()).isEqualTo(Duration.ofDays(2));
        assertThat(config.upkeepPolicy().autoRemediateOnDeposit()).isFalse();
    }
}
