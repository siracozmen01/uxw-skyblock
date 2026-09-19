package com.uxplima.uxmskyblock.bukkit.freeze;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BukkitIslandVisitorEvictionAdapterTest {

    private Plugin plugin;
    private Server server;
    private IslandStoragePort storagePort;
    private DirectScheduler scheduler;
    private BukkitIslandVisitorEvictionAdapter adapter;

    private IslandId islandId;
    private World testWorld;
    private World spawnWorld;
    private Location spawnLocation;

    @BeforeEach
    void setUp() {
        plugin = mock(Plugin.class);
        server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);

        storagePort = mock(IslandStoragePort.class);
        scheduler = new DirectScheduler();
        adapter = new BukkitIslandVisitorEvictionAdapter(plugin, storagePort, scheduler);

        islandId = IslandId.of(UUID.randomUUID());
        testWorld = mock(World.class);
        when(testWorld.getName()).thenReturn("skyblock_world");

        spawnWorld = mock(World.class);
        when(spawnWorld.getName()).thenReturn("spawn_world");
        spawnLocation = new Location(spawnWorld, 0, 64, 0);
        when(spawnWorld.getSpawnLocation()).thenReturn(spawnLocation);

        when(server.getWorlds()).thenReturn(List.of(spawnWorld, testWorld));
    }

    @Test
    @DisplayName("eviction is skipped when island location cannot be found")
    void evictionSkippedWhenLocationNotFound() {
        when(storagePort.findLocationByIslandId(islandId)).thenReturn(Optional.empty());

        adapter.evictNonStaffVisitors(islandId, "Quarantine test");

        verify(server, never()).getOnlinePlayers();
    }

    @Test
    @DisplayName("evicts non-staff visitor standing inside island bounds")
    void evictsVisitorInsideIslandBounds() {
        IslandBounds bounds = IslandBounds.fromCenterAndRadius(0, 0, 100);
        IslandLocation location = new IslandLocation(islandId, "skyblock_world", bounds, 0, 100, 0, 0, 0);
        when(storagePort.findLocationByIslandId(islandId)).thenReturn(Optional.of(location));

        Player visitor = mock(Player.class);
        UUID visitorUuid = UUID.randomUUID();
        when(visitor.getUniqueId()).thenReturn(visitorUuid);
        when(visitor.isOnline()).thenReturn(true);
        when(visitor.isOp()).thenReturn(false);
        when(visitor.hasPermission(any(String.class))).thenReturn(false);

        Location playerLoc = new Location(testWorld, 10, 100, 10);
        when(visitor.getLocation()).thenReturn(playerLoc);
        when(visitor.teleportAsync(spawnLocation)).thenReturn(CompletableFuture.completedFuture(true));

        doReturn(List.of(visitor)).when(server).getOnlinePlayers();

        adapter.evictNonStaffVisitors(islandId, "Quarantine investigation");

        verify(visitor).teleportAsync(spawnLocation);
        verify(visitor).sendMessage(any(net.kyori.adventure.text.Component.class));
    }

    @Test
    @DisplayName("staff member inside island bounds is not evicted")
    void staffMemberNotEvicted() {
        IslandBounds bounds = IslandBounds.fromCenterAndRadius(0, 0, 100);
        IslandLocation location = new IslandLocation(islandId, "skyblock_world", bounds, 0, 100, 0, 0, 0);
        when(storagePort.findLocationByIslandId(islandId)).thenReturn(Optional.of(location));

        Player staff = mock(Player.class);
        UUID staffUuid = UUID.randomUUID();
        when(staff.getUniqueId()).thenReturn(staffUuid);
        when(staff.isOnline()).thenReturn(true);
        when(staff.isOp()).thenReturn(true);

        doReturn(List.of(staff)).when(server).getOnlinePlayers();

        adapter.evictNonStaffVisitors(islandId, "Quarantine investigation");

        verify(staff, never()).teleportAsync(any());
    }

    @Test
    @DisplayName("visitor outside island bounds is not evicted")
    void visitorOutsideBoundsNotEvicted() {
        IslandBounds bounds = IslandBounds.fromCenterAndRadius(0, 0, 100);
        IslandLocation location = new IslandLocation(islandId, "skyblock_world", bounds, 0, 100, 0, 0, 0);
        when(storagePort.findLocationByIslandId(islandId)).thenReturn(Optional.of(location));

        Player visitor = mock(Player.class);
        UUID visitorUuid = UUID.randomUUID();
        when(visitor.getUniqueId()).thenReturn(visitorUuid);
        when(visitor.isOnline()).thenReturn(true);
        when(visitor.isOp()).thenReturn(false);
        when(visitor.hasPermission(any(String.class))).thenReturn(false);

        Location playerLoc = new Location(testWorld, 500, 100, 500); // Outside bounds
        when(visitor.getLocation()).thenReturn(playerLoc);

        doReturn(List.of(visitor)).when(server).getOnlinePlayers();

        adapter.evictNonStaffVisitors(islandId, "Quarantine investigation");

        verify(visitor, never()).teleportAsync(any());
    }

    private static class DirectScheduler implements SchedulerPort {
        @Override
        public void onGlobal(Runnable task) {
            task.run();
        }

        @Override
        public void onRegion(String worldName, int chunkX, int chunkZ, Runnable task) {
            task.run();
        }

        @Override
        public void onEntity(PlayerUuid playerUuid, Runnable task) {
            task.run();
        }

        @Override
        public void async(Runnable task) {
            task.run();
        }

        @Override
        public void asyncAfter(Duration delay, Runnable task) {
            task.run();
        }
    }
}
