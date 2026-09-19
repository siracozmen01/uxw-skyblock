package com.uxplima.uxmskyblock.bukkit.performance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockRedstoneEvent;

import com.uxplima.uxmskyblock.bukkit.config.SettingsConfiguration;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IslandRedstoneOptimizationListenerTest extends MockBukkitHarness {

    private World world;
    private Island island;
    private SettingsConfiguration settingsConfig;

    @BeforeEach
    void setUp() {
        world = server.addSimpleWorld("skyblock_world");
        settingsConfig = SettingsConfiguration.defaultConfiguration();

        UUID ownerUuid = UUID.randomUUID();
        island = Island.create(
                new IslandId(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(0, 0, 50),
                new PlayerUuid(ownerUuid),
                new ProfileId(ownerUuid),
                Instant.now());
    }

    @Test
    @DisplayName("Freezes redstone current to 0 when all island members are offline")
    void freezesRedstoneWhenMembersOffline() {
        // onlineMemberCheck returns false (0 members online)
        IslandRedstoneOptimizationListener listener =
                new IslandRedstoneOptimizationListener(settingsConfig, loc -> Optional.of(island), isl -> false);

        Block redstoneBlock = world.getBlockAt(0, 64, 0);
        redstoneBlock.setType(Material.REDSTONE_WIRE);

        BlockRedstoneEvent event = new BlockRedstoneEvent(redstoneBlock, 0, 15);
        listener.onBlockRedstone(event);

        assertThat(event.getNewCurrent()).isZero();
    }

    @Test
    @DisplayName("Allows redstone propagation when island members are online")
    void allowsRedstoneWhenMembersOnline() {
        // onlineMemberCheck returns true
        IslandRedstoneOptimizationListener listener =
                new IslandRedstoneOptimizationListener(settingsConfig, loc -> Optional.of(island), isl -> true);

        Block redstoneBlock = world.getBlockAt(0, 64, 0);
        redstoneBlock.setType(Material.REDSTONE_WIRE);

        BlockRedstoneEvent event = new BlockRedstoneEvent(redstoneBlock, 0, 15);
        listener.onBlockRedstone(event);

        assertThat(event.getNewCurrent()).isEqualTo(15);
    }

    @Test
    @DisplayName("Cancels piston extension when all island members are offline")
    void cancelsPistonWhenMembersOffline() {
        IslandRedstoneOptimizationListener listener =
                new IslandRedstoneOptimizationListener(settingsConfig, loc -> Optional.of(island), isl -> false);

        Block pistonBlock = world.getBlockAt(0, 64, 0);
        pistonBlock.setType(Material.PISTON);

        BlockPistonExtendEvent event =
                new BlockPistonExtendEvent(pistonBlock, Collections.emptyList(), BlockFace.NORTH);
        listener.onPistonExtend(event);

        assertThat(event.isCancelled()).isTrue();
    }
}
