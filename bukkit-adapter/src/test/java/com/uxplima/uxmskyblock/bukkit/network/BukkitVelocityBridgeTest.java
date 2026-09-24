package com.uxplima.uxmskyblock.bukkit.network;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class BukkitVelocityBridgeTest extends MockBukkitHarness {

    private BukkitVelocityBridge velocityBridge;

    @BeforeEach
    void setUp() {
        velocityBridge = new BukkitVelocityBridge(
                MockBukkit.createMockPlugin(),
                new com.uxplima.uxmskyblock.bukkit.test.InlineSchedulerPort(),
                (player, target) -> java.util.concurrent.CompletableFuture.completedFuture(true));
    }

    @Test
    @DisplayName("routePlayer succeeds when player is online")
    void routePlayerOnlineSuccess() {
        PlayerMock player = createPlayer("Steve");
        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        ServerNodeId targetNode = ServerNodeId.of("skyblock-02");
        IslandId islandId = IslandId.of(UUID.randomUUID());

        boolean result =
                velocityBridge.routePlayer(playerUuid, targetNode, islandId).join();

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("routePlayer returns false when player is offline")
    void routePlayerOfflineFails() {
        PlayerUuid playerUuid = new PlayerUuid(UUID.randomUUID());
        ServerNodeId targetNode = ServerNodeId.of("skyblock-02");
        IslandId islandId = IslandId.of(UUID.randomUUID());

        boolean result =
                velocityBridge.routePlayer(playerUuid, targetNode, islandId).join();

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("routePlayer asks nothing of the proxy when the session could not be handed on")
    void routePlayerWithoutHandoffFails() {
        PlayerMock player = createPlayer("Held");
        BukkitVelocityBridge refusing = new BukkitVelocityBridge(
                MockBukkit.createMockPlugin(),
                new com.uxplima.uxmskyblock.bukkit.test.InlineSchedulerPort(),
                (who, target) -> java.util.concurrent.CompletableFuture.completedFuture(false));

        boolean result = refusing.routePlayer(
                        new PlayerUuid(player.getUniqueId()),
                        ServerNodeId.of("skyblock-02"),
                        IslandId.of(UUID.randomUUID()))
                .join();

        assertThat(result).isFalse();
    }
}
