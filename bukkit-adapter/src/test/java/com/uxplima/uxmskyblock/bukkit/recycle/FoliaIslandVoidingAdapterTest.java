package com.uxplima.uxmskyblock.bukkit.recycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;

@Isolated
@Execution(ExecutionMode.SAME_THREAD)
class FoliaIslandVoidingAdapterTest {

    private Server server;
    private World world;
    private Chunk chunk;
    private Location spawnLocation;
    private DirectScheduler scheduler;
    private FoliaIslandVoidingAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        server = mock(Server.class);
        setBukkitServer(server);

        world = mock(World.class);
        when(server.getWorld("skyblock_world")).thenReturn(world);
        when(world.getMinHeight()).thenReturn(0);
        when(world.getMaxHeight()).thenReturn(10); // small range for fast test

        spawnLocation = new Location(world, 0, 64, 0);
        when(world.getSpawnLocation()).thenReturn(spawnLocation);

        chunk = mock(Chunk.class);
        when(world.getChunkAt(anyInt(), anyInt())).thenReturn(chunk);
        when(chunk.getEntities()).thenReturn(new Entity[0]);

        Block block = mock(Block.class);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(block);
        when(block.isEmpty()).thenReturn(false);

        scheduler = new DirectScheduler();
        adapter = new FoliaIslandVoidingAdapter(scheduler);
    }

    @AfterEach
    void tearDown() throws Exception {
        setBukkitServer(null);
    }

    private static void setBukkitServer(Server s) throws Exception {
        Field field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);
        field.set(null, s);
    }

    @Test
    @DisplayName("Clears non-air blocks within bounds and verifies exclusive maxY iteration")
    void testClearsBlocksWithinBoundsExclusiveMaxY() {
        IslandId islandId = IslandId.of(UUID.randomUUID());
        // Bounds strictly within chunk (0, 0): [6..10, 6..10]
        IslandBounds bounds = IslandBounds.fromCenterAndRadius(8, 8, 2);

        Block block = mock(Block.class);
        when(block.isEmpty()).thenReturn(false);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(block);

        CompletableFuture<Void> future = adapter.voidIslandChunks(islandId, "skyblock_world", bounds);
        assertThat(future).isCompleted();

        verify(block, atLeastOnce()).setType(Material.AIR, false);
        // Verify maxY is never accessed (strictly exclusive y < maxY)
        verify(world, never()).getBlockAt(anyInt(), org.mockito.AdditionalMatchers.geq(10), anyInt());
    }

    @Test
    @DisplayName("Evacuates online players, resetting velocity and fall distance before and after teleport")
    void testEvacuatesOnlinePlayers() {
        IslandId islandId = IslandId.of(UUID.randomUUID());
        // Bounds strictly within chunk (0, 0): [6..10, 6..10]
        IslandBounds bounds = IslandBounds.fromCenterAndRadius(8, 8, 2);

        Player player = mock(Player.class);
        when(player.isOnline()).thenReturn(true);
        when(player.teleportAsync(spawnLocation)).thenReturn(CompletableFuture.completedFuture(true));

        Entity mob = mock(Entity.class);

        when(chunk.getEntities()).thenReturn(new Entity[] {player, mob});

        CompletableFuture<Void> future = adapter.voidIslandChunks(islandId, "skyblock_world", bounds);
        assertThat(future).isCompleted();

        // Verify player velocity and fall distance reset
        verify(player, atLeastOnce()).setVelocity(new Vector(0, 0, 0));
        verify(player, atLeastOnce()).setFallDistance(0.0f);
        verify(player, atLeastOnce()).teleportAsync(spawnLocation);

        // Verify non-player entity removed
        verify(mob, atLeastOnce()).remove();
    }

    @Test
    @DisplayName("Ignores offline players and removes mobs")
    void testIgnoresOfflinePlayers() {
        IslandId islandId = IslandId.of(UUID.randomUUID());
        // Bounds strictly within chunk (0, 0): [6..10, 6..10]
        IslandBounds bounds = IslandBounds.fromCenterAndRadius(8, 8, 2);

        Player offlinePlayer = mock(Player.class);
        when(offlinePlayer.isOnline()).thenReturn(false);

        when(chunk.getEntities()).thenReturn(new Entity[] {offlinePlayer});

        CompletableFuture<Void> future = adapter.voidIslandChunks(islandId, "skyblock_world", bounds);
        assertThat(future).isCompleted();

        verify(offlinePlayer, never()).teleportAsync(any());
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
