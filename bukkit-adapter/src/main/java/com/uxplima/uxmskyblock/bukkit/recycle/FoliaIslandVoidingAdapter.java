package com.uxplima.uxmskyblock.bukkit.recycle;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import com.uxplima.uxmskyblock.core.application.performance.AdaptiveBackpressureController;
import com.uxplima.uxmskyblock.core.application.recycle.IslandVoidingPort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import org.jspecify.annotations.Nullable;

/**
 * Platform adapter executing asynchronous Folia-native chunk voiding, entity evacuation,
 * and block clearing across island bounds.
 */
public final class FoliaIslandVoidingAdapter implements IslandVoidingPort {

    private final SchedulerPort schedulerPort;
    private final @Nullable AdaptiveBackpressureController backpressureController;

    public FoliaIslandVoidingAdapter(
            SchedulerPort schedulerPort, @Nullable AdaptiveBackpressureController backpressureController) {
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.backpressureController = backpressureController;
    }

    public FoliaIslandVoidingAdapter(SchedulerPort schedulerPort) {
        this(schedulerPort, null);
    }

    public @Nullable AdaptiveBackpressureController backpressureController() {
        return backpressureController;
    }

    @Override
    @SuppressWarnings("EmptyCatch")
    public CompletableFuture<Void> voidIslandChunks(IslandId islandId, String worldName, IslandBounds bounds) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(worldName, "worldName must not be null");
        Objects.requireNonNull(bounds, "bounds must not be null");

        int minChunkX = bounds.minX() >> 4;
        int maxChunkX = bounds.maxX() >> 4;
        int minChunkZ = bounds.minZ() >> 4;
        int maxChunkZ = bounds.maxZ() >> 4;

        List<CompletableFuture<Void>> chunkFutures = new ArrayList<>();
        Deque<Runnable> waiting = new ArrayDeque<>();

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                final int chunkX = cx;
                final int chunkZ = cz;
                CompletableFuture<Void> chunkFuture = new CompletableFuture<>();
                chunkFutures.add(chunkFuture);

                waiting.add(() -> schedulerPort.onRegion(worldName, chunkX, chunkZ, () -> {
                    try {
                        World world = Bukkit.getWorld(worldName);
                        if (world == null) {
                            chunkFuture.complete(null);
                            return;
                        }
                        Chunk chunk = world.getChunkAt(chunkX, chunkZ);
                        Location spawn = world.getSpawnLocation();

                        // 1. Evacuate online players from this chunk
                        List<CompletableFuture<Boolean>> playerTeleports = new ArrayList<>();
                        for (Entity entity : chunk.getEntities()) {
                            if (entity instanceof Player player) {
                                if (player.isOnline()) {
                                    player.setVelocity(new Vector(0, 0, 0));
                                    player.setFallDistance(0.0f);
                                    CompletableFuture<Boolean> tpFuture = player.teleportAsync(spawn)
                                            .whenComplete((success, ex) -> {
                                                if (ex == null && Boolean.TRUE.equals(success)) {
                                                    try {
                                                        player.setVelocity(new Vector(0, 0, 0));
                                                        player.setFallDistance(0.0f);
                                                    } catch (Exception ignored) {
                                                        // Ignore if player disconnected
                                                    }
                                                }
                                            });
                                    playerTeleports.add(tpFuture);
                                }
                            } else {
                                entity.remove();
                            }
                        }

                        // 2. Clear blocks within island bounds in this chunk
                        int startX = Math.max(chunkX << 4, bounds.minX());
                        int endX = Math.min((chunkX << 4) + 15, bounds.maxX());
                        int startZ = Math.max(chunkZ << 4, bounds.minZ());
                        int endZ = Math.min((chunkZ << 4) + 15, bounds.maxZ());
                        int minY = world.getMinHeight();
                        int maxY = world.getMaxHeight();

                        for (int x = startX; x <= endX; x++) {
                            for (int z = startZ; z <= endZ; z++) {
                                for (int y = minY; y < maxY; y++) {
                                    Block block = world.getBlockAt(x, y, z);
                                    if (!block.isEmpty()) {
                                        block.setType(Material.AIR, false);
                                    }
                                }
                            }
                        }

                        if (playerTeleports.isEmpty()) {
                            chunkFuture.complete(null);
                        } else {
                            var unused = CompletableFuture.allOf(playerTeleports.toArray(CompletableFuture<?>[]::new))
                                    .whenComplete((res, ex) -> chunkFuture.complete(null));
                        }
                    } catch (Throwable t) {
                        chunkFuture.completeExceptionally(t);
                    }
                }));
            }
        }

        if (chunkFutures.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        startAtTheRateAllowed(waiting);
        return CompletableFuture.allOf(chunkFutures.toArray(CompletableFuture<?>[]::new));
    }

    /**
     * Starts the waiting chunks no faster than the operator allows.
     *
     * <p>Every chunk of the island used to be handed to its region at once. An island of two
     * hundred blocks a side is a hundred and sixty nine of them, each clearing every block in its
     * own column, and all of them landing on the same tick. The operator's file has named a rate
     * for this since the performance work, one number for an ordinary server and a smaller one for
     * a server already behind, and nothing read either.
     *
     * <p>The rate is read again for every second rather than once at the start, so a server that
     * falls behind halfway through a reset finishes the rest of it slower.
     */
    private void startAtTheRateAllowed(Deque<Runnable> waiting) {
        int rate = backpressureController == null
                ? Integer.MAX_VALUE
                : Math.max(1, backpressureController.resolveChunkDeletionRate());
        for (int started = 0; started < rate && !waiting.isEmpty(); started++) {
            waiting.poll().run();
        }
        if (!waiting.isEmpty()) {
            schedulerPort.asyncAfter(Duration.ofSeconds(1), () -> startAtTheRateAllowed(waiting));
        }
    }
}
