package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.StringReader;

import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

class ServerNodeConfigurationTest {

    private static CommentedConfigurationNode parseHocon(String hocon) throws Exception {
        return HoconConfigurationLoader.builder()
                .source(() -> new BufferedReader(new StringReader(hocon)))
                .build()
                .load();
    }

    @Test
    @DisplayName("loads valid server-node configuration from HOCON")
    void loadsValidServerNodeConfiguration() throws Exception {
        String hocon = """
                server-node {
                    id = "skyblock-node-01"
                    world-name = "custom_skyblock"
                }
                """;
        CommentedConfigurationNode node = parseHocon(hocon);
        ServerNodeConfiguration config = ServerNodeConfiguration.load(node);

        assertThat(config.nodeId()).isEqualTo(ServerNodeId.of("skyblock-node-01"));
        assertThat(config.worldName()).isEqualTo("custom_skyblock");
    }

    @Test
    @DisplayName("defaults world-name to 'world' when omitted")
    void defaultsWorldNameToWorldWhenOmitted() throws Exception {
        String hocon = """
                server-node {
                    id = "skyblock-node-02"
                }
                """;
        CommentedConfigurationNode node = parseHocon(hocon);
        ServerNodeConfiguration config = ServerNodeConfiguration.load(node);

        assertThat(config.nodeId()).isEqualTo(ServerNodeId.of("skyblock-node-02"));
        assertThat(config.worldName()).isEqualTo("world");
    }

    @Test
    @DisplayName("fails fast when server-node section is missing")
    void failsFastWhenSectionMissing() throws Exception {
        CommentedConfigurationNode node = parseHocon("");

        assertThatThrownBy(() -> ServerNodeConfiguration.load(node))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Server node identity is missing or blank");
    }

    @Test
    @DisplayName("fails fast when server-node.id is blank")
    void failsFastWhenNodeIdIsBlank() throws Exception {
        String hocon = """
                server-node {
                    id = "   "
                }
                """;
        CommentedConfigurationNode node = parseHocon(hocon);

        assertThatThrownBy(() -> ServerNodeConfiguration.load(node))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Server node identity is missing or blank");
    }

    @Test
    @DisplayName("fails fast when world-name is blank")
    void failsFastWhenWorldNameIsBlank() throws Exception {
        String hocon = """
                server-node {
                    id = "skyblock-node-03"
                    world-name = "   "
                }
                """;
        CommentedConfigurationNode node = parseHocon(hocon);

        assertThatThrownBy(() -> ServerNodeConfiguration.load(node))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("world-name must not be blank");
    }

    @Test
    @DisplayName("loads clustered configuration with default redis uri")
    void loadsClusteredConfigurationWithDefaultRedisUri() throws Exception {
        String hocon = """
                server-node {
                    id = "skyblock-node-cluster-01"
                    clustered = true
                }
                """;
        CommentedConfigurationNode node = parseHocon(hocon);
        ServerNodeConfiguration config = ServerNodeConfiguration.load(node);

        assertThat(config.isClustered()).isTrue();
        assertThat(config.redisUri()).isEqualTo(ServerNodeConfiguration.DEFAULT_REDIS_URI);
    }

    @Test
    @DisplayName("loads custom redis-uri when configured")
    void loadsCustomRedisUri() throws Exception {
        String hocon = """
                server-node {
                    id = "skyblock-node-cluster-02"
                    clustered = true
                    redis-uri = "redis://custom-redis:6380"
                }
                """;
        CommentedConfigurationNode node = parseHocon(hocon);
        ServerNodeConfiguration config = ServerNodeConfiguration.load(node);

        assertThat(config.isClustered()).isTrue();
        assertThat(config.redisUri()).isEqualTo("redis://custom-redis:6380");
    }
}
