package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

class LanguageConfigurationTest {

    @Test
    @DisplayName("The shipped block reads as English with the client followed")
    void shippedBlockReads() throws Exception {
        LanguageConfiguration config = load("""
                language {
                    default = "en"
                    follow-client = true
                }
                """);

        assertThat(config.defaultLanguage()).isEqualTo("en");
        assertThat(config.followClient()).isTrue();
        assertThat(config.defaultLocale()).isEqualTo(Locale.of("en"));
    }

    @Test
    @DisplayName("An operator who names another language gets it, in any case they typed it")
    void anotherLanguageIsRead() throws Exception {
        LanguageConfiguration config = load("""
                language {
                    default = "TR"
                    follow-client = false
                }
                """);

        assertThat(config.defaultLanguage()).isEqualTo("tr");
        assertThat(config.followClient()).isFalse();
    }

    @Test
    @DisplayName("A file with no language block keeps the shipped answer rather than failing")
    void missingBlockFallsBack() throws Exception {
        LanguageConfiguration config = load("server-node { id = \"node-1\" }\n");

        assertThat(config).isEqualTo(LanguageConfiguration.defaults());
    }

    @Test
    @DisplayName("A configuration supplied in code, with no file behind it, keeps the shipped answer")
    void programmaticConfigurationFallsBack() {
        assertThat(LanguageConfiguration.load(null)).isEqualTo(LanguageConfiguration.defaults());
    }

    @Test
    @DisplayName("A blank language is refused rather than silently read as a language")
    void blankLanguageIsRefused() {
        assertThatThrownBy(() -> new LanguageConfiguration("  ", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("language.default");
    }

    @Test
    @DisplayName("A blank language in the file falls back rather than throwing at boot")
    void blankLanguageInFileFallsBack() throws Exception {
        LanguageConfiguration config = load("""
                language {
                    default = ""
                }
                """);

        assertThat(config.defaultLanguage()).isEqualTo("en");
    }

    private static LanguageConfiguration load(String hocon) throws Exception {
        HoconConfigurationLoader loader = HoconConfigurationLoader.builder()
                .source(() -> new BufferedReader(new StringReader(hocon)))
                .build();
        CommentedConfigurationNode root = loader.load();
        return LanguageConfiguration.load(root);
    }
}
