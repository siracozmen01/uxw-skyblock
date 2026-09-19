package com.uxplima.uxmskyblock.bukkit.worth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.worth.IslandWorthService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IslandWorthListenerTest extends MockBukkitHarness {

    private IslandWorthService mockWorthService;
    private IslandProtectionListener mockProtectionListener;
    private IslandWorthListener listener;

    private World world;
    private Island sampleIsland;
    private IslandId islandId;
    private Player player;

    @BeforeEach
    void setUp() {
        world = server.addSimpleWorld("skyblock_world");
        mockWorthService = mock(IslandWorthService.class);
        mockProtectionListener = mock(IslandProtectionListener.class);
        listener = new IslandWorthListener(mockWorthService, mockProtectionListener);

        player = createPlayer("WorthPlayer");
        islandId = new IslandId(UUID.randomUUID());
        sampleIsland = Island.create(
                islandId,
                IslandBounds.fromCenterAndRadius(0, 0, 50),
                new PlayerUuid(player.getUniqueId()),
                new ProfileId(player.getUniqueId()),
                Instant.now());
    }

    @Test
    @DisplayName("onBlockPlace updates block count when inside island boundary")
    void onBlockPlaceInsideIslandUpdatesCount() {
        Block block = world.getBlockAt(10, 64, 10);
        block.setType(Material.DIAMOND_BLOCK);

        when(mockProtectionListener.findIslandAt(block.getLocation())).thenReturn(Optional.of(sampleIsland));

        BlockPlaceEvent event = new BlockPlaceEvent(
                block,
                block.getState(),
                block,
                player.getInventory().getItemInMainHand(),
                player,
                true,
                org.bukkit.inventory.EquipmentSlot.HAND);

        listener.onBlockPlace(event);

        verify(mockWorthService).recordBlockPlace(eq(islandId), eq("minecraft:diamond_block"), eq(1));
    }

    @Test
    @DisplayName("onBlockPlace ignores block placed outside any island")
    void onBlockPlaceOutsideIslandIgnored() {
        Block block = world.getBlockAt(1000, 64, 1000);
        block.setType(Material.EMERALD_BLOCK);

        when(mockProtectionListener.findIslandAt(block.getLocation())).thenReturn(Optional.empty());

        BlockPlaceEvent event = new BlockPlaceEvent(
                block,
                block.getState(),
                block,
                player.getInventory().getItemInMainHand(),
                player,
                true,
                org.bukkit.inventory.EquipmentSlot.HAND);

        listener.onBlockPlace(event);

        verify(mockWorthService, never()).recordBlockPlace(any(), anyString(), anyInt());
    }

    @Test
    @DisplayName("onBlockBreak updates block count when inside island boundary")
    void onBlockBreakInsideIslandUpdatesCount() {
        Block block = world.getBlockAt(15, 64, 15);
        block.setType(Material.GOLD_BLOCK);

        when(mockProtectionListener.findIslandAt(block.getLocation())).thenReturn(Optional.of(sampleIsland));

        BlockBreakEvent event = new BlockBreakEvent(block, player);
        listener.onBlockBreak(event);

        verify(mockWorthService).recordBlockBreak(eq(islandId), eq("minecraft:gold_block"), eq(1));
    }

    @Test
    @DisplayName("onBlockBreak ignores block broken outside any island")
    void onBlockBreakOutsideIslandIgnored() {
        Block block = world.getBlockAt(2000, 64, 2000);
        block.setType(Material.GOLD_BLOCK);

        when(mockProtectionListener.findIslandAt(block.getLocation())).thenReturn(Optional.empty());

        BlockBreakEvent event = new BlockBreakEvent(block, player);
        listener.onBlockBreak(event);

        verify(mockWorthService, never()).recordBlockBreak(any(), anyString(), anyInt());
    }
}
