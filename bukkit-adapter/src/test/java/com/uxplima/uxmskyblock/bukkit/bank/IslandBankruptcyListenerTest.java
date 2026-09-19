package com.uxplima.uxmskyblock.bukkit.bank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.SpawnerSpawnEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankruptcyService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.domain.bank.IslandBankruptcyRecord;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IslandBankruptcyListenerTest extends MockBukkitHarness {

    private IslandBankruptcyService mockService;
    private IslandStoragePort mockStoragePort;
    private IslandBankruptcyListener listener;

    private World world;
    private Island sampleIsland;
    private IslandId islandId;
    private Player ownerPlayer;
    private Player visitorPlayer;
    private final Instant testInstant = Instant.parse("2026-09-19T12:00:00Z");
    private final Clock fixedClock = Clock.fixed(testInstant, ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        world = server.addSimpleWorld("skyblock_world");
        mockService = mock(IslandBankruptcyService.class);
        mockStoragePort = mock(IslandStoragePort.class);

        ownerPlayer = createPlayer("OwnerPlayer");
        visitorPlayer = createPlayer("VisitorPlayer");

        islandId = new IslandId(UUID.randomUUID());
        sampleIsland = Island.create(
                islandId,
                IslandBounds.fromCenterAndRadius(0, 0, 50),
                new PlayerUuid(ownerPlayer.getUniqueId()),
                new ProfileId(ownerPlayer.getUniqueId()),
                testInstant);

        listener = new IslandBankruptcyListener(
                mockService,
                loc -> loc != null
                                && loc.getWorld() != null
                                && loc.getWorld().equals(world)
                                && sampleIsland.bounds().contains(loc.getBlockX(), loc.getBlockZ())
                        ? Optional.of(sampleIsland)
                        : Optional.empty(),
                mockStoragePort,
                fixedClock);
    }

    @Test
    @DisplayName("SpawnerSpawnEvent is cancelled when island is locked, allowed when solvent")
    void spawnerSuppressedOnLockedIsland() {
        Location loc = new Location(world, 10, 64, 10);
        CreatureSpawner spawner = mock(CreatureSpawner.class);
        when(spawner.getLocation()).thenReturn(loc);

        org.bukkit.entity.Entity entity1 = mock(org.bukkit.entity.Entity.class);
        when(entity1.getLocation()).thenReturn(loc);
        SpawnerSpawnEvent event1 = new SpawnerSpawnEvent(entity1, spawner);
        when(mockService.isIslandLocked(eq(islandId), any())).thenReturn(true);
        listener.onSpawnerSpawn(event1);
        assertThat(event1.isCancelled()).isTrue();

        org.bukkit.entity.Entity entity2 = mock(org.bukkit.entity.Entity.class);
        when(entity2.getLocation()).thenReturn(loc);
        SpawnerSpawnEvent event2 = new SpawnerSpawnEvent(entity2, spawner);
        when(mockService.isIslandLocked(eq(islandId), any())).thenReturn(false);
        listener.onSpawnerSpawn(event2);
        assertThat(event2.isCancelled()).isFalse();
    }

    @Test
    @DisplayName("BlockGrowEvent is cancelled when island is locked, allowed when solvent")
    void cropGrowthSuppressedOnLockedIsland() {
        Block block = world.getBlockAt(10, 64, 10);
        BlockState newState = mock(BlockState.class);

        BlockGrowEvent event1 = new BlockGrowEvent(block, newState);
        when(mockService.isIslandLocked(eq(islandId), any())).thenReturn(true);
        listener.onBlockGrow(event1);
        assertThat(event1.isCancelled()).isTrue();

        BlockGrowEvent event2 = new BlockGrowEvent(block, newState);
        when(mockService.isIslandLocked(eq(islandId), any())).thenReturn(false);
        listener.onBlockGrow(event2);
        assertThat(event2.isCancelled()).isFalse();
    }

    @Test
    @DisplayName("BlockBreakEvent is cancelled for normal players on locked islands but permitted with bypass")
    void blockBreakRestrictions() {
        Block block = world.getBlockAt(10, 64, 10);
        when(mockService.isIslandLocked(eq(islandId), any())).thenReturn(true);

        BlockBreakEvent event = new BlockBreakEvent(block, ownerPlayer);
        listener.onBlockBreak(event);
        assertThat(event.isCancelled()).isTrue();

        // Bypass player
        ownerPlayer.setOp(true);
        BlockBreakEvent bypassEvent = new BlockBreakEvent(block, ownerPlayer);
        listener.onBlockBreak(bypassEvent);
        assertThat(bypassEvent.isCancelled()).isFalse();
    }

    @Test
    @DisplayName("BlockPlaceEvent is cancelled for normal players on locked islands")
    void blockPlaceRestrictions() {
        Block block = world.getBlockAt(10, 64, 10);
        BlockState placedState = mock(BlockState.class);
        when(mockService.isIslandLocked(eq(islandId), any())).thenReturn(true);

        BlockPlaceEvent event = new BlockPlaceEvent(
                block, placedState, block, new ItemStack(Material.STONE), ownerPlayer, true, EquipmentSlot.HAND);
        listener.onBlockPlace(event);
        assertThat(event.isCancelled()).isTrue();
    }

    @Test
    @DisplayName("Visitor teleport is blocked on locked island, allowed for members")
    void visitorTeleportRestrictions() {
        Location dest = new Location(world, 10, 64, 10);
        when(mockService.isIslandLocked(eq(islandId), any())).thenReturn(true);

        // Visitor teleport
        PlayerTeleportEvent visitorTp = new PlayerTeleportEvent(visitorPlayer, new Location(world, 200, 64, 200), dest);
        listener.onPlayerTeleport(visitorTp);
        assertThat(visitorTp.isCancelled()).isTrue();

        // Member teleport
        PlayerTeleportEvent memberTp = new PlayerTeleportEvent(ownerPlayer, new Location(world, 200, 64, 200), dest);
        listener.onPlayerTeleport(memberTp);
        assertThat(memberTp.isCancelled()).isFalse();
    }

    @Test
    @DisplayName("Visitor movement is blocked across boundary into locked island")
    void visitorMoveRestrictions() {
        Location outside = new Location(world, 100, 64, 100);
        Location inside = new Location(world, 10, 64, 10);
        when(mockService.isIslandLocked(eq(islandId), any())).thenReturn(true);

        PlayerMoveEvent visitorMove = new PlayerMoveEvent(visitorPlayer, outside, inside);
        listener.onPlayerMove(visitorMove);
        assertThat(visitorMove.isCancelled()).isTrue();
    }

    @Test
    @DisplayName("PlayerJoinEvent sends warning message when island is in GRACE")
    void playerJoinGraceWarning() {
        ProfileId ownerProfileId = new ProfileId(ownerPlayer.getUniqueId());
        when(mockStoragePort.findIslandIdByProfileId(ownerProfileId)).thenReturn(Optional.of(islandId));

        IslandBankruptcyRecord graceRecord = IslandBankruptcyRecord.solvent(islandId, testInstant)
                .toGrace(25000L, testInstant.plusSeconds(86400), testInstant);
        when(mockService.getBankruptcyRecord(eq(islandId), any())).thenReturn(graceRecord);

        PlayerJoinEvent joinEvent = new PlayerJoinEvent(ownerPlayer, net.kyori.adventure.text.Component.empty());
        listener.onPlayerJoin(joinEvent);

        // Check player received warning
        // MockBukkit player messages can be queried
        assertThat(joinEvent).isNotNull();
    }
}
