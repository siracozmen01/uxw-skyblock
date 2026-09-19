package com.uxplima.uxmskyblock.bukkit.limit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.limit.IslandLimitService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.limit.LimitType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IslandLimitListenerTest extends MockBukkitHarness {

    private IslandLimitService mockLimitService;
    private IslandProtectionListener mockProtectionListener;
    private IslandLimitListener listener;

    private World world;
    private Island sampleIsland;
    private IslandId islandId;
    private Player player;

    private static final String BYPASS_PERM = "uxmskyblock.bypass.limits";

    @BeforeEach
    void setUp() {
        world = server.addSimpleWorld("skyblock_world");
        mockLimitService = mock(IslandLimitService.class);
        mockProtectionListener = mock(IslandProtectionListener.class);
        listener = new IslandLimitListener(mockLimitService, mockProtectionListener, BYPASS_PERM);

        player = createPlayer("LimitPlayer");
        islandId = new IslandId(UUID.randomUUID());
        sampleIsland = Island.create(
                islandId,
                IslandBounds.fromCenterAndRadius(0, 0, 50),
                new PlayerUuid(player.getUniqueId()),
                new ProfileId(player.getUniqueId()),
                Instant.now());

        when(mockProtectionListener.findIslandAt(any())).thenReturn(Optional.of(sampleIsland));
    }

    @Test
    @DisplayName("onBlockPlace succeeds and increments when below limit")
    void blockPlaceSucceedsBelowLimit() {
        Block block = world.getBlockAt(0, 64, 0);
        block.setType(Material.HOPPER);

        when(mockLimitService.tryIncrement(islandId, LimitType.HOPPER, false)).thenReturn(true);

        BlockPlaceEvent event = new BlockPlaceEvent(
                block, block.getState(), block, new ItemStack(Material.HOPPER), player, true, EquipmentSlot.HAND);

        listener.onBlockPlace(event);

        assertThat(event.isCancelled()).isFalse();
        verify(mockLimitService).tryIncrement(islandId, LimitType.HOPPER, false);
    }

    @Test
    @DisplayName("onBlockPlace cancels event when limit reached")
    void blockPlaceCancelledWhenLimitReached() {
        Block block = world.getBlockAt(0, 64, 0);
        block.setType(Material.HOPPER);

        when(mockLimitService.tryIncrement(islandId, LimitType.HOPPER, false)).thenReturn(false);
        when(mockLimitService.getCount(islandId, LimitType.HOPPER)).thenReturn(50);
        when(mockLimitService.getEffectiveLimit(islandId, LimitType.HOPPER)).thenReturn(50);

        BlockPlaceEvent event = new BlockPlaceEvent(
                block, block.getState(), block, new ItemStack(Material.HOPPER), player, true, EquipmentSlot.HAND);

        listener.onBlockPlace(event);

        assertThat(event.isCancelled()).isTrue();
    }

    @Test
    @DisplayName("onBlockPlace allows exceeding limit when player has bypass permission")
    void blockPlaceAllowedWithBypass() {
        Block block = world.getBlockAt(0, 64, 0);
        block.setType(Material.HOPPER);

        player.addAttachment(org.mockbukkit.mockbukkit.MockBukkit.createMockPlugin(), BYPASS_PERM, true);
        when(mockLimitService.tryIncrement(islandId, LimitType.HOPPER, true)).thenReturn(true);

        BlockPlaceEvent event = new BlockPlaceEvent(
                block, block.getState(), block, new ItemStack(Material.HOPPER), player, true, EquipmentSlot.HAND);

        listener.onBlockPlace(event);

        assertThat(event.isCancelled()).isFalse();
        verify(mockLimitService).tryIncrement(islandId, LimitType.HOPPER, true);
    }

    @Test
    @DisplayName("onBlockPlace ignores untracked blocks")
    void blockPlaceIgnoresUntrackedBlocks() {
        Block block = world.getBlockAt(0, 64, 0);
        block.setType(Material.STONE);

        BlockPlaceEvent event = new BlockPlaceEvent(
                block, block.getState(), block, new ItemStack(Material.STONE), player, true, EquipmentSlot.HAND);

        listener.onBlockPlace(event);

        assertThat(event.isCancelled()).isFalse();
        verify(mockLimitService, never()).tryIncrement(any(), any(), any(Boolean.class));
    }

    @Test
    @DisplayName("onBlockBreak decrements limit count for tracked block")
    void blockBreakDecrementsCount() {
        Block block = world.getBlockAt(0, 64, 0);
        block.setType(Material.HOPPER);

        BlockBreakEvent event = new BlockBreakEvent(block, player);
        listener.onBlockBreak(event);

        verify(mockLimitService).decrement(islandId, LimitType.HOPPER);
    }

    @Test
    @DisplayName("onEntitySpawn succeeds and increments when below limit")
    void entitySpawnSucceedsBelowLimit() {
        Villager villager =
                (Villager) world.spawnEntity(world.getBlockAt(0, 64, 0).getLocation(), EntityType.VILLAGER);
        when(mockLimitService.tryIncrement(islandId, LimitType.VILLAGER, false)).thenReturn(true);

        EntitySpawnEvent event = new EntitySpawnEvent(villager);
        listener.onEntitySpawn(event);

        assertThat(event.isCancelled()).isFalse();
        verify(mockLimitService).tryIncrement(islandId, LimitType.VILLAGER, false);
    }

    @Test
    @DisplayName("onEntitySpawn cancels when entity limit reached")
    void entitySpawnCancelledWhenLimitReached() {
        Villager villager =
                (Villager) world.spawnEntity(world.getBlockAt(0, 64, 0).getLocation(), EntityType.VILLAGER);
        when(mockLimitService.tryIncrement(islandId, LimitType.VILLAGER, false)).thenReturn(false);

        EntitySpawnEvent event = new EntitySpawnEvent(villager);
        listener.onEntitySpawn(event);

        assertThat(event.isCancelled()).isTrue();
    }

    @Test
    @DisplayName("onEntityDeath decrements entity limit count")
    void entityDeathDecrementsCount() {
        Villager villager =
                (Villager) world.spawnEntity(world.getBlockAt(0, 64, 0).getLocation(), EntityType.VILLAGER);

        org.bukkit.damage.DamageSource damageSource = org.bukkit.damage.DamageSource.builder(
                        org.bukkit.damage.DamageType.GENERIC)
                .build();
        EntityDeathEvent event = new EntityDeathEvent(villager, damageSource, new java.util.ArrayList<>());
        listener.onEntityDeath(event);

        verify(mockLimitService).decrement(islandId, LimitType.VILLAGER);
    }
}
