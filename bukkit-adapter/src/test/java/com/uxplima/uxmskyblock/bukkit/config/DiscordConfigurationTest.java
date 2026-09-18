package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.uxplima.uxmskyblock.core.domain.discord.DiscordTopic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

class DiscordConfigurationTest {

    @Test
    @DisplayName("loads discord configuration correctly from valid HOCON")
    void loadsDiscordConfigurationFromValidHocon() throws Exception {
        String hocon = """
                discord {
                  enabled = true
                  bot-username = "Skyblock Announcer"
                  avatar-url = "https://cdn.example.com/logo.png"
                  rate-limit-per-second = 3.5
                  webhooks {
                    milestones = "https://discord.com/api/webhooks/123/milestones"
                    leaderboards = "https://discord.com/api/webhooks/123/leaders"
                    alliances = "https://discord.com/api/webhooks/123/allies"
                    admin-audit = "https://discord.com/api/webhooks/123/admin"
                  }
                }
                """;

        ConfigurationNode root = HoconConfigurationLoader.builder().buildAndLoadString(hocon);
        DiscordConfiguration config = DiscordConfiguration.load(root);

        assertThat(config.enabled()).isTrue();
        assertThat(config.botUsername()).isEqualTo("Skyblock Announcer");
        assertThat(config.avatarUrl()).isEqualTo("https://cdn.example.com/logo.png");
        assertThat(config.rateLimitPerSecond()).isEqualTo(3.5);
        assertThat(config.webhookUrls()).hasSize(4);
        assertThat(config.webhookUrls().get(DiscordTopic.MILESTONES))
                .isEqualTo("https://discord.com/api/webhooks/123/milestones");
        assertThat(config.webhookUrls().get(DiscordTopic.ADMIN_AUDIT))
                .isEqualTo("https://discord.com/api/webhooks/123/admin");
    }

    @Test
    @DisplayName("defaults properly when discord section is missing")
    void defaultsWhenMissing() throws Exception {
        ConfigurationNode root = HoconConfigurationLoader.builder().buildAndLoadString("");
        DiscordConfiguration config = DiscordConfiguration.load(root);

        assertThat(config.enabled()).isFalse();
        assertThat(config.botUsername()).isEqualTo(DiscordConfiguration.DEFAULT_BOT_USERNAME);
        assertThat(config.avatarUrl()).isNull();
        assertThat(config.rateLimitPerSecond()).isEqualTo(DiscordConfiguration.DEFAULT_RATE_LIMIT);
        assertThat(config.webhookUrls()).isEmpty();
    }

    @Test
    @DisplayName("rejects null configuration node")
    @SuppressWarnings("NullAway")
    void rejectsNullNode() {
        ConfigurationNode nullNode = null;
        assertThatThrownBy(() -> DiscordConfiguration.load(nullNode)).isInstanceOf(NullPointerException.class);
    }
}
