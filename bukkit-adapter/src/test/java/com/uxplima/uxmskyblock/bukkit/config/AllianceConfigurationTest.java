package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

class AllianceConfigurationTest {

    @Test
    @DisplayName("Loads default configuration when node is missing")
    void loadsDefaultWhenMissing() {
        CommentedConfigurationNode root = CommentedConfigurationNode.root();
        AllianceConfiguration config = AllianceConfiguration.load(root);

        assertThat(config.enabled()).isTrue();
        assertThat(config.maxAllies()).isEqualTo(2);
        assertThat(config.inviteTimeout()).isEqualTo(Duration.ofMinutes(5));
        assertThat(config.friendlyFireShielding()).isTrue();
        assertThat(config.privilegedVisitAccess()).isTrue();
        assertThat(config.allianceChatEnabled()).isTrue();
    }

    @Test
    @DisplayName("Loads custom configuration from HOCON string")
    void loadsCustomHocon() throws IOException {
        String hocon = """
                alliances {
                    enabled = false
                    max-allies = 5
                    invite-timeout = "10m"
                    friendly-fire-shielding = false
                    privileged-visit-access = false
                    alliance-chat-enabled = false
                }
                """;

        ConfigurationNode root = HoconConfigurationLoader.builder().buildAndLoadString(hocon);
        AllianceConfiguration config = AllianceConfiguration.load(root);

        assertThat(config.enabled()).isFalse();
        assertThat(config.maxAllies()).isEqualTo(5);
        assertThat(config.inviteTimeout()).isEqualTo(Duration.ofMinutes(10));
        assertThat(config.friendlyFireShielding()).isFalse();
        assertThat(config.privilegedVisitAccess()).isFalse();
        assertThat(config.allianceChatEnabled()).isFalse();
    }
}
