package com.uxplima.uxmskyblock.bukkit.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.bukkit.World;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class MockBukkitHarnessTest extends MockBukkitHarness {

    @Test
    @DisplayName("server initializes and creates mock players and worlds")
    void serverInitializesProperly() {
        assertThat(server).isNotNull();

        PlayerMock player = createPlayer("Steve");
        assertThat(player).isNotNull();
        assertThat(player.getName()).isEqualTo("Steve");
        assertThat(player.isOnline()).isTrue();

        World world = server.addSimpleWorld("skyblock_test_world");
        assertThat(world).isNotNull();
        assertThat(world.getName()).isEqualTo("skyblock_test_world");
    }
}
