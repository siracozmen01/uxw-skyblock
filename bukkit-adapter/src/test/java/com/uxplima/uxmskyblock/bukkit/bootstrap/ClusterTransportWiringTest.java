package com.uxplima.uxmskyblock.bukkit.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import org.bukkit.plugin.java.JavaPlugin;

import com.uxplima.uxmskyblock.bukkit.config.ServerNodeConfiguration;
import com.uxplima.uxmskyblock.core.application.chat.LocalIslandChatTransportAdapter;
import com.uxplima.uxmskyblock.core.application.event.LocalEventTransport;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClusterTransportWiringTest {

    @Test
    @DisplayName("standalone mode creates local event transport and local chat transport")
    void standaloneModeCreatesLocalTransports() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        ServerNodeConfiguration config =
                ServerNodeConfiguration.of(ServerNodeId.of("node-standalone"), "world", false, "");

        try (ClusterTransportWiring wiring = ClusterTransportWiring.create(plugin, config)) {
            assertThat(wiring.isClustered()).isFalse();
            assertThat(wiring.eventTransport()).isInstanceOf(LocalEventTransport.class);
            assertThat(wiring.chatTransport()).isInstanceOf(LocalIslandChatTransportAdapter.class);
            assertThat(wiring.clusterRoutingDirectory()).isNotNull();
        }
    }

    @Test
    @DisplayName("clustered mode fails closed when redis is unreachable")
    void clusteredModeFailsClosedWhenRedisUnreachable() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        // Using an invalid unreachable port for redis
        ServerNodeConfiguration config =
                ServerNodeConfiguration.of(ServerNodeId.of("node-clustered"), "world", true, "redis://127.0.0.1:59999");

        assertThatThrownBy(() -> ClusterTransportWiring.create(plugin, config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Fail-closed");
    }
}
