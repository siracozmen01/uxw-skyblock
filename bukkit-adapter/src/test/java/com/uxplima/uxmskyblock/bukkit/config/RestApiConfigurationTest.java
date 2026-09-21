package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.StringReader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

class RestApiConfigurationTest {

    @Test
    @DisplayName("The shipped block keeps the API closed")
    void shippedBlockIsClosed() throws Exception {
        RestApiConfiguration config = load("""
                rest {
                    enabled = false
                    host = "127.0.0.1"
                    port = 8080
                    bearer-token = "change-me"
                }
                """);

        assertThat(config.enabled()).isFalse();
        assertThat(config.shouldStart()).isFalse();
        assertThat(config.refusedForDefaultToken()).isFalse();
    }

    @Test
    @DisplayName("Enabling it while the shipped token is still in place does not open the port")
    void defaultTokenIsRefused() throws Exception {
        RestApiConfiguration config = load("""
                rest {
                    enabled = true
                    bearer-token = "change-me"
                }
                """);

        assertThat(config.shouldStart()).isFalse();
        assertThat(config.refusedForDefaultToken()).isTrue();
    }

    @Test
    @DisplayName("A blank token is refused the same way as the shipped one")
    void blankTokenIsRefused() throws Exception {
        RestApiConfiguration config = load("""
                rest {
                    enabled = true
                    bearer-token = ""
                }
                """);

        assertThat(config.shouldStart()).isFalse();
        assertThat(config.refusedForDefaultToken()).isTrue();
    }

    @Test
    @DisplayName("An operator who turns it on and sets a token gets the port")
    void configuredApiStarts() throws Exception {
        RestApiConfiguration config = load("""
                rest {
                    enabled = true
                    host = "0.0.0.0"
                    port = 9090
                    bearer-token = "a-real-secret"
                }
                """);

        assertThat(config.shouldStart()).isTrue();
        assertThat(config.host()).isEqualTo("0.0.0.0");
        assertThat(config.port()).isEqualTo(9090);
        assertThat(config.toRestConfiguration().bearerToken()).isEqualTo("a-real-secret");
    }

    @Test
    @DisplayName("A file with no rest block keeps the API closed rather than failing")
    void missingBlockIsClosed() throws Exception {
        assertThat(load("server-node { id = \"node-1\" }\n").shouldStart()).isFalse();
        assertThat(RestApiConfiguration.load(null)).isEqualTo(RestApiConfiguration.defaults());
    }

    private static RestApiConfiguration load(String hocon) throws Exception {
        HoconConfigurationLoader loader = HoconConfigurationLoader.builder()
                .source(() -> new BufferedReader(new StringReader(hocon)))
                .build();
        CommentedConfigurationNode root = loader.load();
        return RestApiConfiguration.load(root);
    }
}
