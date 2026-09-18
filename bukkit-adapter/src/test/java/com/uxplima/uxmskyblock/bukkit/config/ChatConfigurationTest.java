package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

class ChatConfigurationTest {

    @Test
    @DisplayName("Loads default configuration when node is missing")
    void loadsDefaultWhenMissing() {
        CommentedConfigurationNode root = CommentedConfigurationNode.root();
        ChatConfiguration config = ChatConfiguration.load(root);

        assertThat(config.enabled()).isTrue();
        assertThat(config.format()).isEqualTo(ChatConfiguration.DEFAULT_FORMAT);
        assertThat(config.spyFormat()).isEqualTo(ChatConfiguration.DEFAULT_SPY_FORMAT);
        assertThat(config.rateLimitMessagesPerSecond()).isEqualTo(5);
    }

    @Test
    @DisplayName("Loads custom configuration from HOCON string")
    void loadsCustomHocon() throws IOException {
        String hocon = """
                chat {
                    enabled = false
                    format = "<red>[Team]</red> <player>: <message>"
                    spy-format = "<gray>[Spy]</gray> <island_name> <player>: <message>"
                    rate-limit-messages-per-second = 10
                }
                """;

        ConfigurationNode root = HoconConfigurationLoader.builder().buildAndLoadString(hocon);
        ChatConfiguration config = ChatConfiguration.load(root);

        assertThat(config.enabled()).isFalse();
        assertThat(config.format()).isEqualTo("<red>[Team]</red> <player>: <message>");
        assertThat(config.spyFormat()).isEqualTo("<gray>[Spy]</gray> <island_name> <player>: <message>");
        assertThat(config.rateLimitMessagesPerSecond()).isEqualTo(10);
    }
}
