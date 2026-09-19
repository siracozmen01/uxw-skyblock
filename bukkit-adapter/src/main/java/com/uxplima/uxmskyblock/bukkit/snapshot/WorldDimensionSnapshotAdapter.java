package com.uxplima.uxmskyblock.bukkit.snapshot;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;

import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.snapshot.WorldDimensionSnapshotPort;
import com.uxplima.uxmskyblock.core.domain.dimension.DimensionId;
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
    private static final int SNAPSHOT_FORMAT_VERSION = 2;

    private record CapturedBlock(int x, int y, int z, String blockData) {}

    private final Plugin plugin;
    private final @Nullable IslandStoragePort islandStoragePort;

    public WorldDimensionSnapshotAdapter(Plugin plugin, @Nullable IslandStoragePort islandStoragePort) {
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
        this.islandStoragePort = islandStoragePort;
    }

    public WorldDimensionSnapshotAdapter(Plugin plugin) {
        this(plugin, null);
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

                List<CapturedBlock> nonAirBlocks = new ArrayList<>();
                for (int cx = minChunkX; cx <= maxChunkX; cx++) {
                    for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                        Chunk chunk = targetWorld.getChunkAt(cx, cz);
                        int startX = Math.max(cx << 4, bounds.minX());
                        int endX = Math.min((cx << 4) + 15, bounds.maxX());
                        int startZ = Math.max(cz << 4, bounds.minZ());
                        int endZ = Math.min((cz << 4) + 15, bounds.maxZ());

                        for (int x = startX; x <= endX; x++) {
                            for (int z = startZ; z <= endZ; z++) {
                                for (int y = minY; y < maxY; y++) {
                                    Block block = chunk.getBlock(x & 15, y, z & 15);
                                    if (!block.isEmpty()) {
                                        nonAirBlocks.add(new CapturedBlock(
                                                x, y, z, block.getBlockData().getAsString()));
                                    }
                                }
                            }
                        }
                    }
                }

                dos.writeInt(nonAirBlocks.size());
                for (CapturedBlock cb : nonAirBlocks) {
                    dos.writeInt(cb.x);
                    dos.writeInt(cb.y);
                    dos.writeInt(cb.z);
                    dos.writeUTF(cb.blockData);
                }
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
            if (formatVersion != SNAPSHOT_FORMAT_VERSION && formatVersion != SNAPSHOT_FORMAT_VERSION_LEGACY) {
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
                    dis.readInt(); // centerX
                    dis.readInt(); // centerZ
                    dis.readInt(); // radius

                    int blockCount = dis.readInt();
                    World targetWorld = findWorldForDimension(dimensionId);
                    if (targetWorld == null && Bukkit.getServer() != null) {
                        targetWorld = Bukkit.getWorld(worldName);
                    }

                    for (int i = 0; i < blockCount; i++) {
                        int x = dis.readInt();
                        int y = dis.readInt();
                        int z = dis.readInt();
                        String blockDataStr = dis.readUTF();

                        if (targetWorld != null) {
                            try {
                                Block block = targetWorld.getBlockAt(x, y, z);
                                block.setBlockData(Bukkit.createBlockData(blockDataStr), false);
                            } catch (Exception e) {
                                plugin.getLogger()
                                        .fine(() -> "Skipping invalid block state restore: " + e.getMessage());
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(
                    "Failed to deserialize world dimension snapshot for root " + rootRef.rootId(), e);
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
        return IslandBounds.fromCenterAndRadius(0, 0, 16);
    }

    private static World findWorldForDimension(DimensionId dimensionId) {
        try {
            if (Bukkit.getServer() == null) {
                return null;
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
