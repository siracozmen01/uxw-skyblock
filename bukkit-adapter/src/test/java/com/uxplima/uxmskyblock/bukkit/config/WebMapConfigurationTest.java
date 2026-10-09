package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.uxplima.uxmskyblock.core.application.webmap.MarkerLook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/** The web map's layer, words and colours are read from {@code modules/webmap.conf}. */
class WebMapConfigurationTest {

    @Test
    @DisplayName("A file that writes a key is read, and a key it leaves out keeps the shipped value")
    void theFileIsRead() throws Exception {
        String hocon = """
                webmap {
                    layer = "Adalar"
                    top { border = "#112233" }
                    words { owner = "Sahip" }
                }
                """;
        var node = HoconConfigurationLoader.builder()
                .source(() -> new java.io.BufferedReader(new java.io.StringReader(hocon)))
                .build()
                .load();

        WebMapConfiguration config = WebMapConfiguration.load(node);

        assertThat(config.layer()).isEqualTo("Adalar");
        assertThat(config.look().top().border()).isEqualTo("#112233");
        assertThat(config.look().top().fill())
                .isEqualTo(MarkerLook.palette().top().fill());
        assertThat(config.look().words().owner()).isEqualTo("Sahip");
        assertThat(config.look().words().bank())
                .isEqualTo(MarkerLook.palette().words().bank());
    }

    @Test
    @DisplayName("The shipped file says what the code ships, so a fresh server and an empty file agree")
    void theShippedFileMatchesTheDefaults() throws Exception {
        var url = java.util.Objects.requireNonNull(getClass().getClassLoader().getResource("modules/webmap.conf"));
        var node = HoconConfigurationLoader.builder().url(url).build().load();

        assertThat(WebMapConfiguration.load(node)).isEqualTo(WebMapConfiguration.defaultConfiguration());
    }
}
