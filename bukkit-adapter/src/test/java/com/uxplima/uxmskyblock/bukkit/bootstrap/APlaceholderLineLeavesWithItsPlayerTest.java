package com.uxplima.uxmskyblock.bukkit.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.uxplima.uxmskyblock.bukkit.integration.placeholder.SkyblockPlaceholderExpansion;
import com.uxplima.uxmskyblock.bukkit.integration.placeholder.SkyblockPlaceholderExpansion.CachedPlayerIsland;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * The placeholder cache kept a line for every player who ever joined, because nothing on the
 * running server ever told it that one had left.
 */
class APlaceholderLineLeavesWithItsPlayerTest extends MockBukkitHarness {

    private SkyblockPlaceholderExpansion expansion;

    @BeforeEach
    void setUp() {
        server.addSimpleWorld("world");
        UxMSkyblockPlugin plugin = MockBukkit.load(UxMSkyblockPlugin.class);
        expansion = plugin.bootstrap().placeholderExpansion();
    }

    @Test
    @DisplayName("A player who leaves takes their cached placeholder line with them")
    void aPlayerWhoLeavesIsForgotten() {
        PlayerMock player = createPlayer("Leaver");
        expansion.cacheData(player.getUniqueId(), CachedPlayerIsland.empty());

        player.disconnect();

        assertThat(expansion.holds(player.getUniqueId())).isFalse();
    }

    @Test
    @DisplayName("A player who stays keeps their cached placeholder line")
    void aPlayerWhoStaysIsKept() {
        PlayerMock player = createPlayer("Stayer");
        expansion.cacheData(player.getUniqueId(), CachedPlayerIsland.empty());

        assertThat(expansion.holds(player.getUniqueId())).isTrue();
    }
}
