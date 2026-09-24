package com.uxplima.uxmskyblock.bukkit.snapshot;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.Plugin;

import com.uxplima.uxmskyblock.bukkit.config.DimensionConfiguration;
import com.uxplima.uxmskyblock.bukkit.performance.BudgetedBlockPass;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.performance.AdaptiveBackpressureController;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.snapshot.WorldDimensionSnapshotPort;
import com.uxplima.uxmskyblock.core.domain.dimension.DimensionId;
import com.uxplima.uxmskyblock.core.domain.dimension.DimensionMapping;
import com.uxplima.uxmskyblock.core.domain.dimension.IslandDimensionType;
import com.uxplima.uxmskyblock.core.domain.gamemode.PrimaryGameplayRootRef;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import org.jspecify.annotations.Nullable;

/**
 * Bukkit/Paper adapter for extracting chunk geometry, non-air block state, and coordinating
 * Folia region threading during world dimension snapshots (Section 2.29 & Section 9).
 */
public final class WorldDimensionSnapshotAdapter implements WorldDimensionSnapshotPort {

    private static final int SNAPSHOT_FORMAT_VERSION_LEGACY = 1;
    private static final int SNAPSHOT_FORMAT_VERSION_BLOCKS = 2;
    /** Blocks, then the island's creatures that carry nothing. */
    private static final int SNAPSHOT_FORMAT_VERSION = 3;

    private record CapturedBlock(int x, int y, int z, String blockData) {}

    private record BlockCoord(int x, int y, int z) {}

    private final Plugin plugin;
    private final @Nullable IslandStoragePort islandStoragePort;
    private final @Nullable SchedulerPort schedulerPort;
    private final @Nullable DimensionConfiguration dimensionConfiguration;
    private final @Nullable AdaptiveBackpressureController backpressureController;

    public WorldDimensionSnapshotAdapter(
            Plugin plugin,
            @Nullable IslandStoragePort islandStoragePort,
            @Nullable SchedulerPort schedulerPort,
            @Nullable DimensionConfiguration dimensionConfiguration) {
        this(plugin, islandStoragePort, schedulerPort, dimensionConfiguration, null);
    }

    public WorldDimensionSnapshotAdapter(
            Plugin plugin,
            @Nullable IslandStoragePort islandStoragePort,
            @Nullable SchedulerPort schedulerPort,
            @Nullable DimensionConfiguration dimensionConfiguration,
            @Nullable AdaptiveBackpressureController backpressureController) {
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
        this.islandStoragePort = islandStoragePort;
        this.schedulerPort = schedulerPort;
        this.dimensionConfiguration = dimensionConfiguration;
        this.backpressureController = backpressureController;
    }

    /** How long a capture waits for its regions when the operator's file says nothing. */
    public static final Duration DEFAULT_CAPTURE_TIMEOUT = Duration.ofSeconds(30);

    private volatile Duration captureTimeout = DEFAULT_CAPTURE_TIMEOUT;

    /**
     * How long a capture waits for every region to hand its chunks over before it gives up whole.
     *
     * @see CaptureDeadline
     */
    public void captureWithin(Duration timeout) {
        this.captureTimeout = CaptureDeadline.checked(timeout);
    }

    /** How many blocks one tick may put back, or every one of them on a node with no controller. */
    private int blocksPerTick() {
        return backpressureController == null
                ? Integer.MAX_VALUE
                : Math.max(1, backpressureController.resolveBlockPasteBatchSize());
    }

    public WorldDimensionSnapshotAdapter(Plugin plugin, @Nullable IslandStoragePort islandStoragePort) {
        this(plugin, islandStoragePort, null, null);
    }

    public WorldDimensionSnapshotAdapter(Plugin plugin) {
        this(plugin, null, null, null);
    }

    public Plugin plugin() {
        return plugin;
    }

    @Override
    public byte[] captureWorldDimension(PrimaryGameplayRootRef rootRef, DimensionId dimensionId) {
        Objects.requireNonNull(rootRef, "rootRef must not be null");
        Objects.requireNonNull(dimensionId, "dimensionId must not be null");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos);
                DataOutputStream dos = new DataOutputStream(gzos)) {

            dos.writeInt(SNAPSHOT_FORMAT_VERSION);
            dos.writeUTF(rootRef.gameModeInstanceId().value().toString());
            dos.writeUTF(rootRef.rootId());
            dos.writeUTF(rootRef.rootType());
            dos.writeUTF(dimensionId.value());
            dos.writeLong(System.currentTimeMillis());

            // Check if world is loaded in Bukkit
            World targetWorld = findWorldForDimension(dimensionId);
            if (targetWorld != null) {
                dos.writeBoolean(true);
                dos.writeUTF(targetWorld.getName());

                IslandBounds bounds = resolveBounds(rootRef);
                dos.writeInt(bounds.centerX());
                dos.writeInt(bounds.centerZ());
                dos.writeInt(bounds.radius());

                int minChunkX = bounds.minX() >> 4;
                int maxChunkX = bounds.maxX() >> 4;
                int minChunkZ = bounds.minZ() >> 4;
                int maxChunkZ = bounds.maxZ() >> 4;
                int minY = targetWorld.getMinHeight();
                int maxY = targetWorld.getMaxHeight();

                List<CapturedBlock> nonAirBlocks = Collections.synchronizedList(new ArrayList<>());
                Map<java.util.UUID, SnapshotEntities.Captured> entities =
                        new java.util.concurrent.ConcurrentHashMap<>();
                List<CompletableFuture<Void>> chunkFutures = new ArrayList<>();

                for (int cx = minChunkX; cx <= maxChunkX; cx++) {
                    for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                        final int finalCx = cx;
                        final int finalCz = cz;
                        CompletableFuture<Void> chunkFuture = new CompletableFuture<>();

                        Runnable chunkTask = () -> {
                            try {
                                Chunk chunk = targetWorld.getChunkAt(finalCx, finalCz);
                                int startX = Math.max(finalCx << 4, bounds.minX());
                                int endX = Math.min((finalCx << 4) + 15, bounds.maxX());
                                int startZ = Math.max(finalCz << 4, bounds.minZ());
                                int endZ = Math.min((finalCz << 4) + 15, bounds.maxZ());

                                List<CapturedBlock> chunkBlocks = new ArrayList<>();
                                for (int x = startX; x <= endX; x++) {
                                    for (int z = startZ; z <= endZ; z++) {
                                        for (int y = minY; y < maxY; y++) {
                                            Block block = chunk.getBlock(x & 15, y, z & 15);
                                            if (!block.isEmpty()) {
                                                chunkBlocks.add(new CapturedBlock(
                                                        x,
                                                        y,
                                                        z,
                                                        block.getBlockData().getAsString()));
                                            }
                                        }
                                    }
                                }
                                nonAirBlocks.addAll(chunkBlocks);
                                SnapshotEntities.captureFrom(chunk, bounds, entities);
                                chunkFuture.complete(null);
                            } catch (Throwable t) {
                                chunkFuture.completeExceptionally(t);
                            }
                        };

                        if (schedulerPort != null) {
                            schedulerPort.onRegion(targetWorld.getName(), finalCx, finalCz, chunkTask);
                        } else {
                            chunkTask.run();
                        }
                        chunkFutures.add(chunkFuture);
                    }
                }

                CaptureDeadline.await(chunkFutures, captureTimeout);

                dos.writeInt(nonAirBlocks.size());
                for (CapturedBlock cb : nonAirBlocks) {
                    dos.writeInt(cb.x);
                    dos.writeInt(cb.y);
                    dos.writeInt(cb.z);
                    dos.writeUTF(cb.blockData);
                }
                SnapshotEntities.write(dos, entities.values());
            } else {
                dos.writeBoolean(false);
            }

            dos.flush();
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize world dimension snapshot for root " + rootRef.rootId(), e);
        }

        return baos.toByteArray();
    }

    @Override
    public void restoreWorldDimension(
            PrimaryGameplayRootRef rootRef, DimensionId dimensionId, byte[] dimensionPayload) {
        Objects.requireNonNull(rootRef, "rootRef must not be null");
        Objects.requireNonNull(dimensionId, "dimensionId must not be null");
        Objects.requireNonNull(dimensionPayload, "dimensionPayload must not be null");

        try (ByteArrayInputStream bais = new ByteArrayInputStream(dimensionPayload);
                GZIPInputStream gzis = new GZIPInputStream(bais);
                DataInputStream dis = new DataInputStream(gzis)) {

            int formatVersion = dis.readInt();
            if (formatVersion != SNAPSHOT_FORMAT_VERSION
                    && formatVersion != SNAPSHOT_FORMAT_VERSION_BLOCKS
                    && formatVersion != SNAPSHOT_FORMAT_VERSION_LEGACY) {
                throw new IllegalArgumentException("Unsupported snapshot format version: " + formatVersion);
            }

            dis.readUTF(); // instanceId
            String rootId = dis.readUTF();
            dis.readUTF(); // rootType
            String recordedDim = dis.readUTF();
            dis.readLong(); // timestamp

            if (!rootId.equalsIgnoreCase(rootRef.rootId())) {
                throw new IllegalArgumentException(
                        "Dimension snapshot rootId mismatch: expected " + rootRef.rootId() + " but found " + rootId);
            }
            if (!recordedDim.equalsIgnoreCase(dimensionId.value())) {
                throw new IllegalArgumentException("Dimension snapshot dimensionId mismatch: expected "
                        + dimensionId.value() + " but found " + recordedDim);
            }

            boolean hasWorld = dis.readBoolean();
            if (hasWorld) {
                String worldName = dis.readUTF();
                if (formatVersion == SNAPSHOT_FORMAT_VERSION_LEGACY) {
                    dis.readInt(); // chunkCount placeholder
                } else {
                    int centerX = dis.readInt();
                    int centerZ = dis.readInt();
                    int radius = dis.readInt();
                    IslandBounds bounds = IslandBounds.fromCenterAndRadius(centerX, centerZ, radius);

                    int blockCount = dis.readInt();
                    World targetWorld = findWorldForDimension(dimensionId);
                    if (targetWorld == null && Bukkit.getServer() != null) {
                        targetWorld = Bukkit.getWorld(worldName);
                    }

                    Map<Long, Map<BlockCoord, String>> chunkBlockMap = new HashMap<>();
                    for (int i = 0; i < blockCount; i++) {
                        int x = dis.readInt();
                        int y = dis.readInt();
                        int z = dis.readInt();
                        String blockDataStr = dis.readUTF();

                        int cx = x >> 4;
                        int cz = z >> 4;
                        long chunkKey = (((long) cx) << 32) | (cz & 0xFFFFFFFFL);
                        chunkBlockMap
                                .computeIfAbsent(chunkKey, k -> new HashMap<>())
                                .put(new BlockCoord(x, y, z), blockDataStr);
                    }

                    if (targetWorld != null) {
                        int minChunkX = bounds.minX() >> 4;
                        int maxChunkX = bounds.maxX() >> 4;
                        int minChunkZ = bounds.minZ() >> 4;
                        int maxChunkZ = bounds.maxZ() >> 4;
                        int minY = targetWorld.getMinHeight();
                        int maxY = targetWorld.getMaxHeight();

                        List<CompletableFuture<Void>> chunkFutures = new ArrayList<>();
                        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
                            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                                final int finalCx = cx;
                                final int finalCz = cz;
                                final long chunkKey = (((long) cx) << 32) | (cz & 0xFFFFFFFFL);
                                final World finalWorld = targetWorld;
                                CompletableFuture<Void> chunkFuture = new CompletableFuture<>();

                                Runnable chunkTask = () -> {
                                    try {
                                        Chunk chunk = finalWorld.getChunkAt(finalCx, finalCz);
                                        int startX = Math.max(finalCx << 4, bounds.minX());
                                        int endX = Math.min((finalCx << 4) + 15, bounds.maxX());
                                        int startZ = Math.max(finalCz << 4, bounds.minZ());
                                        int endZ = Math.min((finalCz << 4) + 15, bounds.maxZ());

                                        // 1. Remove dropped items (ECONOMIC_ITEM_STATE) within chunk bounds
                                        try {
                                            for (org.bukkit.entity.Entity entity : chunk.getEntities()) {
                                                if (entity instanceof org.bukkit.entity.Item item) {
                                                    org.bukkit.Location loc = item.getLocation();
                                                    if (loc.getBlockX() >= startX
                                                            && loc.getBlockX() <= endX
                                                            && loc.getBlockZ() >= startZ
                                                            && loc.getBlockZ() <= endZ) {
                                                        item.remove();
                                                    }
                                                }
                                            }
                                        } catch (Exception expected) {
                                            // Ignored if chunk entities cannot be queried on this platform
                                        }

                                        // 2. Deterministic replacement of blocks & ECONOMIC_ITEM_STATE container wipe
                                        Map<BlockCoord, String> snapshotBlocks =
                                                chunkBlockMap.getOrDefault(chunkKey, Map.of());

                                        // Putting a chunk back used to be every block in its
                                        // column on one tick, and an island is a hundred and more
                                        // chunks of that. The budget is the operator's, read again
                                        // for every slice.
                                        var unused = BudgetedBlockPass.over(
                                                        schedulerPort,
                                                        finalWorld.getName(),
                                                        finalCx,
                                                        finalCz,
                                                        startX,
                                                        endX,
                                                        startZ,
                                                        endZ,
                                                        minY,
                                                        maxY,
                                                        this::blocksPerTick,
                                                        (x, y, z) -> {
                                                            Block block = chunk.getBlock(x & 15, y, z & 15);
                                                            String blockDataStr =
                                                                    snapshotBlocks.get(new BlockCoord(x, y, z));
                                                            if (blockDataStr != null) {
                                                                clearContainerIfPresent(block);
                                                                try {
                                                                    block.setBlockData(
                                                                            Bukkit.createBlockData(blockDataStr),
                                                                            false);
                                                                } catch (Exception e) {
                                                                    plugin.getLogger()
                                                                            .fine(() ->
                                                                                    "Skipping invalid block state restore: "
                                                                                            + e.getMessage());
                                                                }
                                                                clearContainerIfPresent(block);
                                                                return true;
                                                            }
                                                            if (!block.isEmpty()) {
                                                                // Not in the snapshot: clear it, deterministically.
                                                                clearContainerIfPresent(block);
                                                                block.setType(Material.AIR, false);
                                                                return true;
                                                            }
                                                            return false;
                                                        })
                                                .whenComplete((res, ex) -> {
                                                    if (ex == null) {
                                                        chunkFuture.complete(null);
                                                    } else {
                                                        chunkFuture.completeExceptionally(ex);
                                                    }
                                                });
                                    } catch (Throwable t) {
                                        chunkFuture.completeExceptionally(t);
                                    }
                                };

                                if (schedulerPort != null) {
                                    schedulerPort.onRegion(finalWorld.getName(), finalCx, finalCz, chunkTask);
                                } else {
                                    chunkTask.run();
                                }
                                chunkFutures.add(chunkFuture);
                            }
                        }

                        CompletableFuture.allOf(chunkFutures.toArray(CompletableFuture<?>[]::new))
                                .join();
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(
                    "Failed to deserialize world dimension snapshot for root " + rootRef.rootId(), e);
        }
    }

    private static void clearContainerIfPresent(Block block) {
        try {
            if (block.getState() instanceof InventoryHolder holder) {
                holder.getInventory().clear();
            }
        } catch (Exception expected) {
            // Ignored if block state is not an accessible container
        }
    }

    /**
     * Brings back the creatures the payload holds, after its blocks. A payload written before
     * creatures were captured holds none, and brings none back.
     */
    @Override
    public void restoreEntities(PrimaryGameplayRootRef rootRef, DimensionId dimensionId, byte[] dimensionPayload) {
        Objects.requireNonNull(rootRef, "rootRef must not be null");
        Objects.requireNonNull(dimensionId, "dimensionId must not be null");
        Objects.requireNonNull(dimensionPayload, "dimensionPayload must not be null");
        Optional<SnapshotEntities.Payload> read = SnapshotEntities.readPayload(
                dimensionPayload, SNAPSHOT_FORMAT_VERSION, rootRef.rootId(), dimensionId.value());
        if (read.isEmpty()) {
            return;
        }
        World world = findWorldForDimension(dimensionId);
        if (world == null && Bukkit.getServer() != null) {
            world = Bukkit.getWorld(read.get().worldName());
        }
        if (world != null) {
            SnapshotEntities.spawnMissing(world, read.get().bounds(), read.get().captured(), schedulerPort);
        }
    }

    private IslandBounds resolveBounds(PrimaryGameplayRootRef rootRef) {
        if (islandStoragePort != null) {
            try {
                IslandId islandId = IslandId.fromString(rootRef.rootId());
                Optional<IslandLocation> loc = islandStoragePort.findLocationByIslandId(islandId);
                if (loc.isPresent()) {
                    return loc.get().bounds();
                }
            } catch (Exception e) {
                plugin.getLogger().fine(() -> "Could not resolve island bounds from storage: " + e.getMessage());
            }
        }
        throw new IllegalStateException("Failed to resolve island bounds from storage for root: " + rootRef.rootId());
    }

    private @Nullable World findWorldForDimension(DimensionId dimensionId) {
        try {
            if (Bukkit.getServer() == null) {
                return null;
            }
            if (dimensionConfiguration != null && dimensionConfiguration.enabled()) {
                IslandDimensionType type = IslandDimensionType.fromKey(dimensionId.value());
                DimensionMapping mapping = dimensionConfiguration.mappings().get(type);
                if (mapping != null && mapping.worldName() != null) {
                    World world = Bukkit.getWorld(mapping.worldName());
                    if (world != null) {
                        return world;
                    }
                }
            }
            String val = dimensionId.value();
            return switch (val) {
                case "overworld" ->
                    Bukkit.getWorld("world") != null ? Bukkit.getWorld("world") : Bukkit.getWorld("skyblock_world");
                case "the_nether", "nether" ->
                    Bukkit.getWorld("world_nether") != null
                            ? Bukkit.getWorld("world_nether")
                            : Bukkit.getWorld("skyblock_world_nether");
                case "the_end", "end" ->
                    Bukkit.getWorld("world_the_end") != null
                            ? Bukkit.getWorld("world_the_end")
                            : Bukkit.getWorld("skyblock_world_the_end");
                default -> Bukkit.getWorld(val);
            };
        } catch (Exception e) {
            return null;
        }
    }
}
