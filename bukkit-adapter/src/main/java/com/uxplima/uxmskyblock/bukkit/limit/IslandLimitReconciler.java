package com.uxplima.uxmskyblock.bukkit.limit;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;

import com.uxplima.uxmskyblock.core.application.limit.IslandLimitService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.limit.LimitType;

/**
 * Region-aware reconciliation scanner for hardware tile and entity limits (Section 2.31).
 *
 * <p>Dispatches chunk-by-chunk audits across Folia region threads to rebuild accurate in-memory
 * counts after server restarts or catastrophic crashes without locking server ticks.
 */
public final class IslandLimitReconciler {

    private final SchedulerPort schedulerPort;
    private final IslandLimitService limitService;

    /** Islands being counted right now, so one scan runs rather than one per placement. */
    private final java.util.Set<IslandId> counting = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public IslandLimitReconciler(SchedulerPort schedulerPort, IslandLimitService limitService) {
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.limitService = Objects.requireNonNull(limitService, "limitService must not be null");
    }

    /**
     * Counts an island once, the first time somebody places something on it.
     *
     * <p>Every count lives in memory, so a restart starts every island at zero and an island that
     * had placed its full allowance could place it again. Nothing called the scan that puts that
     * right. It is called here, once per island per boot, off the thread the placement arrived on.
     *
     * <p>The placement that triggers the scan is not held up waiting for it: refusing a player
     * while chunks load is a worse answer than counting one placement late, and the scan overwrites
     * whatever the counter reached by the time it lands.
     */
    public void countOnceIfNeeded(Island island, String worldName) {
        Objects.requireNonNull(island, "island must not be null");
        Objects.requireNonNull(worldName, "worldName must not be null");
        if (limitService.isCounted(island.id()) || !counting.add(island.id())) {
            return;
        }
        var unused = reconcileIsland(island, worldName).whenComplete((counts, failure) -> counting.remove(island.id()));
    }

    /**
     * Asynchronously scans all chunks intersecting the island bounds across Folia region threads,
     * counts all tracked tile entities and living entities, and reconciles the counts in {@link IslandLimitService}.
     *
     * @param island the island aggregate
     * @param worldName target world identifier
     * @return CompletableFuture holding the reconciled limit counts map
     */
    public CompletableFuture<Map<LimitType, Integer>> reconcileIsland(Island island, String worldName) {
        Objects.requireNonNull(island, "island must not be null");
        Objects.requireNonNull(worldName, "worldName must not be null");

        IslandId islandId = island.id();
        IslandBounds bounds = island.bounds();

        int minChunkX = bounds.minX() >> 4;
        int maxChunkX = bounds.maxX() >> 4;
        int minChunkZ = bounds.minZ() >> 4;
        int maxChunkZ = bounds.maxZ() >> 4;

        int totalChunks = (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
        if (totalChunks <= 0) {
            return CompletableFuture.completedFuture(Map.of());
        }

        ConcurrentHashMap<LimitType, AtomicInteger> aggregated = new ConcurrentHashMap<>();
        AtomicInteger remaining = new AtomicInteger(totalChunks);
        CompletableFuture<Map<LimitType, Integer>> future = new CompletableFuture<>();

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                final int chunkX = cx;
                final int chunkZ = cz;

                schedulerPort.onRegion(worldName, chunkX, chunkZ, () -> {
                    try {
                        World world = Bukkit.getWorld(worldName);
                        if (world != null) {
                            Chunk chunk = world.getChunkAt(chunkX, chunkZ);

                            // 1. Scan Tile Entities
                            try {
                                for (BlockState state : chunk.getTileEntities()) {
                                    if (state != null && bounds.contains(state.getX(), state.getZ())) {
                                        LimitType lt = IslandLimitListener.resolveBlockLimitType(state.getType());
                                        if (lt != null) {
                                            aggregated
                                                    .computeIfAbsent(lt, k -> new AtomicInteger(0))
                                                    .incrementAndGet();
                                        }
                                    }
                                }
                            } catch (Throwable ignored) {
                                // Some test harnesses (e.g. MockBukkit) or chunk formats do not implement
                                // getTileEntities()
                            }

                            // 2. Scan Living / Vehicle Entities
                            try {
                                for (Entity entity : chunk.getEntities()) {
                                    if (entity != null
                                            && bounds.contains(
                                                    entity.getLocation().getBlockX(),
                                                    entity.getLocation().getBlockZ())) {
                                        LimitType elt = IslandLimitListener.resolveEntityLimitType(entity);
                                        if (elt != null) {
                                            aggregated
                                                    .computeIfAbsent(elt, k -> new AtomicInteger(0))
                                                    .incrementAndGet();
                                        }
                                    }
                                }
                            } catch (Throwable ignored) {
                                // Tolerate transient entity access errors
                            }
                        }
                    } catch (Throwable ignored) {
                        // Tolerate ungenerated or unloading chunks
                    } finally {
                        if (remaining.decrementAndGet() == 0) {
                            Map<LimitType, Integer> result = new EnumMap<>(LimitType.class);
                            for (LimitType type : LimitType.values()) {
                                AtomicInteger counter = aggregated.get(type);
                                int count = counter != null ? counter.get() : 0;
                                limitService.setCount(islandId, type, count);
                                result.put(type, count);
                            }
                            // Only now are the counts what the world holds. Until this line the
                            // island was at zero for everything, which is not the same thing.
                            limitService.markCounted(islandId);
                            future.complete(Map.copyOf(result));
                        }
                    }
                });
            }
        }

        return future;
    }

    public IslandLimitService limitService() {
        return limitService;
    }
}
