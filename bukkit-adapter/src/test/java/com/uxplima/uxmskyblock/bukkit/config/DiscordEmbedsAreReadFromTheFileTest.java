package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.application.discord.DiscordEmbedTexts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/** What the Discord embeds say is read from {@code modules/discord.conf}. */
class DiscordEmbedsAreReadFromTheFileTest {

    private static DiscordConfiguration loaded(String hocon) throws Exception {
        ConfigurationNode root = HoconConfigurationLoader.builder().buildAndLoadString(hocon);
        return DiscordConfiguration.load(root);
    }

    @Test
    @DisplayName("The file the plugin ships says exactly what the code falls back to")
    void theShippedFileAgreesWithTheFallback() throws Exception {
        String shipped;
        try (InputStream in = Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream("modules/discord.conf"), "modules/discord.conf")) {
            shipped = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(loaded(shipped).embeds()).isEqualTo(DiscordEmbedTexts.english());
    }

    @Test
    @DisplayName("An operator who changes one title changes only that title")
    void oneKeyChangesOneText() throws Exception {
        DiscordConfiguration config = loaded("""
                discord {
                  enabled = true
                  embeds { milestone { title = "Yeni seviye!", color = "#112233" } }
                }
                """);

        DiscordEmbedTexts.Milestone milestone = config.embeds().milestone();
        assertThat(milestone.title()).isEqualTo("Yeni seviye!");
        assertThat(milestone.color()).isEqualTo(0x112233);
        assertThat(milestone.description())
                .isEqualTo(DiscordEmbedTexts.english().milestone().description());
        assertThat(config.embeds().leaderboard())
                .isEqualTo(DiscordEmbedTexts.english().leaderboard());
    }

    @Test
    @DisplayName("A colour that is not #RRGGBB keeps the shipped colour")
    void aBadColourKeepsTheShippedOne() throws Exception {
        DiscordConfiguration config = loaded("""
                discord {
                  embeds { audit { high-color = "red", low-color = "#12345" } }
                }
                """);

        assertThat(config.embeds().audit())
                .isEqualTo(DiscordEmbedTexts.english().audit());
    }
}
