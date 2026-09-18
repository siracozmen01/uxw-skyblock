package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

class RewardInboxConfigurationTest {

    @Test
    @DisplayName("Loads default configuration when node is missing")
    void loadsDefaultWhenMissing() {
        CommentedConfigurationNode root = CommentedConfigurationNode.root();
        RewardInboxConfiguration config = RewardInboxConfiguration.load(root);

        assertThat(config.enabled()).isTrue();
        assertThat(config.defaultExpiration()).isEqualTo(Duration.ofDays(30));
        assertThat(config.expiryCheckInterval()).isEqualTo(Duration.ofMinutes(5));
        assertThat(config.maxInboxCapacity()).isEqualTo(50);
        assertThat(config.autoClaimOnJoin()).isFalse();
    }

    @Test
    @DisplayName("Loads custom configuration from HOCON string")
    void loadsCustomHocon() throws IOException {
        String hocon = """
                rewards {
                    enabled = false
                    default-expiration = "14d"
                    expiry-check-interval = "10m"
                    max-inbox-capacity = 100
                    auto-claim-on-join = true
                }
                """;

        ConfigurationNode root = HoconConfigurationLoader.builder().buildAndLoadString(hocon);
        RewardInboxConfiguration config = RewardInboxConfiguration.load(root);

        assertThat(config.enabled()).isFalse();
        assertThat(config.defaultExpiration()).isEqualTo(Duration.ofDays(14));
        assertThat(config.expiryCheckInterval()).isEqualTo(Duration.ofMinutes(10));
        assertThat(config.maxInboxCapacity()).isEqualTo(100);
        assertThat(config.autoClaimOnJoin()).isTrue();
    }
}
