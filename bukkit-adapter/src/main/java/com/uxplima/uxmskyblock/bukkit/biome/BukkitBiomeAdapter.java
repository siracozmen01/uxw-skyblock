package com.uxplima.uxmskyblock.bukkit.biome;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Biome;

import com.uxplima.uxmskyblock.core.application.biome.BiomeModificationPort;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.biome.IslandBiome;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;

/**
 * Folia-aware implementation of {@link BiomeModificationPort}.
 *
 * <p>Partitions the island territory into distinct chunk coordinates and dispatches
 * biome block updates onto each chunk's owning {@link org.bukkit.World} region thread
 * via {@link SchedulerPort#onRegion(String, int, int, Runnable)}. Asynchronously aggregates
 * chunk completion without blocking tick threads or accessing Bukkit state on async worker threads.
 */
public final class BukkitBiomeAdapter implements BiomeModificationPort {

    private final IslandStoragePort islandStoragePort;
    private final SchedulerPort schedulerPort;
    private final String worldName;

    public BukkitBiomeAdapter(IslandStoragePort islandStoragePort, SchedulerPort schedulerPort, String worldName) {
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.worldName = Objects.requireNonNull(worldName, "worldName must not be null");
    }

    @Override
    public CompletableFuture<Boolean> applyBiome(IslandId islandId, IslandBiome biome) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(biome, "biome must not be null");

        CompletableFuture<Boolean> completionFuture = new CompletableFuture<>();

        // Step 1: Query storage asynchronously off tick threads
        schedulerPort.async(() -> {
            var optIsland = islandStoragePort.findIslandById(islandId);
            if (optIsland.isEmpty()) {
                completionFuture.complete(false);
                return;
            }

            Island island = optIsland.get();
            IslandBounds bounds = island.bounds();
            Biome targetBiome = mapToBukkitBiome(biome);

            int minChunkX = bounds.minX() >> 4;
            int maxChunkX = bounds.maxX() >> 4;
            int minChunkZ = bounds.minZ() >> 4;
            int maxChunkZ = bounds.maxZ() >> 4;

            List<CompletableFuture<Void>> chunkFutures = new ArrayList<>();

            for (int cx = minChunkX; cx <= maxChunkX; cx++) {
                for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                    final int chunkX = cx;
                    final int chunkZ = cz;
                    CompletableFuture<Void> chunkFuture = new CompletableFuture<>();
                    chunkFutures.add(chunkFuture);

                    // Step 2: Dispatch updates onto chunk region thread
                    schedulerPort.onRegion(worldName, chunkX, chunkZ, () -> {
                        try {
                            World world = Bukkit.getWorld(worldName);
                            if (world == null) {
                                chunkFuture.completeExceptionally(
                                        new IllegalStateException("World '" + worldName + "' is not loaded"));
                                return;
                            }

                            int startX = Math.max(bounds.minX(), chunkX << 4);
                            int endX = Math.min(bounds.maxX(), (chunkX << 4) + 15);
                            int startZ = Math.max(bounds.minZ(), chunkZ << 4);
                            int endZ = Math.min(bounds.maxZ(), (chunkZ << 4) + 15);

                            for (int x = startX; x <= endX; x += 4) {
                                for (int z = startZ; z <= endZ; z += 4) {
                                    for (int y = world.getMinHeight(); y < world.getMaxHeight(); y += 4) {
                                        world.setBiome(x, y, z, targetBiome);
                                    }
                                }
                            }
                            chunkFuture.complete(null);
                        } catch (Throwable t) {
                            chunkFuture.completeExceptionally(t);
                        }
                    });
                }
            }

            // Step 3: Aggregate completion asynchronously
            CompletableFuture.allOf(chunkFutures.toArray(new CompletableFuture<?>[0]))
                    .thenRun(() -> completionFuture.complete(true))
                    .exceptionally(ex -> {
                        completionFuture.complete(false);
                        return null;
                    });
        });

        return completionFuture;
    }

    public static Biome mapToBukkitBiome(IslandBiome biome) {
        return switch (biome) {
            case PLAINS -> Biome.PLAINS;
            case DESERT -> Biome.DESERT;
            case NETHER_WASTES -> Biome.NETHER_WASTES;
            case JUNGLE -> Biome.JUNGLE;
            case SNOWY_PLAINS -> Biome.SNOWY_PLAINS;
            case FLOWER_FOREST -> Biome.FLOWER_FOREST;
            case SWAMP -> Biome.SWAMP;
            case THE_END -> Biome.THE_END;
        };
    }
}
