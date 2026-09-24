package com.uxplima.uxmskyblock.bukkit.snapshot;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import org.jspecify.annotations.Nullable;

/**
 * The island's creatures in a world snapshot: which are taken, how they are written, how they come back.
 *
 * <p>Only a creature that carries nothing is taken. Anything holding an item, wearing one, or keeping
 * an inventory is economic item state, and the specification never restores that from a backup: a
 * zombie in a diamond helmet brought back beside the helmet it dropped since is a duplicated helmet.
 *
 * <p>A capture reads each region on its own, while creatures keep moving, so one that walks across a
 * chunk edge can be read twice. Creatures are kept by their id and written once. A creature brought
 * back is marked with the id it had, so a restore that runs again leaves the one that is there.
 */
final class SnapshotEntities {

    /** The mark a brought back creature carries: the id it had when it was captured. */
    static final NamespacedKey ORIGINAL =
            Objects.requireNonNull(NamespacedKey.fromString("uxmskyblock:snapshot_entity"));

    /** A creature as written into the snapshot: plain values, never a live entity. */
    record Captured(UUID id, String type, double x, double y, double z, float yaw, float pitch) {}

    private SnapshotEntities() {}

    /** Takes every creature in {@code chunk} that stands inside the island and carries nothing. */
    static void captureFrom(Chunk chunk, IslandBounds bounds, Map<UUID, Captured> into) {
        for (Entity entity : chunk.getEntities()) {
            Location at = entity.getLocation();
            if (!inside(bounds, at.getBlockX(), at.getBlockZ()) || !carriesNothing(entity)) {
                continue;
            }
            into.putIfAbsent(
                    entity.getUniqueId(),
                    new Captured(
                            entity.getUniqueId(),
                            entity.getType().getKey().toString(),
                            at.getX(),
                            at.getY(),
                            at.getZ(),
                            at.getYaw(),
                            at.getPitch()));
        }
    }

    /** Whether {@code entity} is a creature that holds no item of any kind. */
    static boolean carriesNothing(Entity entity) {
        if (!(entity instanceof LivingEntity living)
                || entity instanceof Player
                || entity instanceof ArmorStand
                || entity instanceof InventoryHolder) {
            return false;
        }
        EntityEquipment equipment = living.getEquipment();
        if (equipment == null) {
            return true;
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack held;
            try {
                held = equipment.getItem(slot);
            } catch (IllegalArgumentException notASlotItHas) {
                continue;
            }
            if (held != null && !held.getType().isAir()) {
                return false;
            }
        }
        return true;
    }

    static void write(DataOutputStream out, Collection<Captured> captured) throws IOException {
        out.writeInt(captured.size());
        for (Captured entity : captured) {
            out.writeLong(entity.id().getMostSignificantBits());
            out.writeLong(entity.id().getLeastSignificantBits());
            out.writeUTF(entity.type());
            out.writeDouble(entity.x());
            out.writeDouble(entity.y());
            out.writeDouble(entity.z());
            out.writeFloat(entity.yaw());
            out.writeFloat(entity.pitch());
        }
    }

    static List<Captured> read(DataInputStream in) throws IOException {
        int count = in.readInt();
        List<Captured> captured = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            captured.add(new Captured(
                    new UUID(in.readLong(), in.readLong()),
                    in.readUTF(),
                    in.readDouble(),
                    in.readDouble(),
                    in.readDouble(),
                    in.readFloat(),
                    in.readFloat()));
        }
        return captured;
    }

    /** The part of a payload the creatures need: the world it was taken in, the island, the creatures. */
    record Payload(String worldName, IslandBounds bounds, List<Captured> captured) {}

    /**
     * Reads the creatures of a payload written as {@code creaturesVersion}, or nothing when the payload
     * is an older one that held no creatures, or holds no world.
     */
    static java.util.Optional<Payload> readPayload(
            byte[] payload, int creaturesVersion, String expectedRoot, String expectedDimension) {
        try (DataInputStream in =
                new DataInputStream(new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(payload)))) {
            if (in.readInt() != creaturesVersion) {
                return java.util.Optional.empty();
            }
            in.readUTF(); // instanceId
            String rootId = in.readUTF();
            in.readUTF(); // rootType
            String dimension = in.readUTF();
            in.readLong(); // timestamp
            if (!rootId.equalsIgnoreCase(expectedRoot) || !dimension.equalsIgnoreCase(expectedDimension)) {
                throw new IllegalArgumentException("The snapshot is for " + rootId + " in " + dimension + ", not "
                        + expectedRoot + " in " + expectedDimension);
            }
            if (!in.readBoolean()) {
                return java.util.Optional.empty();
            }
            String worldName = in.readUTF();
            IslandBounds bounds = IslandBounds.fromCenterAndRadius(in.readInt(), in.readInt(), in.readInt());
            int blocks = in.readInt();
            for (int i = 0; i < blocks; i++) {
                in.readInt();
                in.readInt();
                in.readInt();
                in.readUTF();
            }
            return java.util.Optional.of(new Payload(worldName, bounds, read(in)));
        } catch (IOException e) {
            throw new IllegalStateException(
                    "The creatures of a snapshot for " + expectedRoot + " could not be read", e);
        }
    }

    /**
     * Brings back every captured creature that is not already there, each in the region that owns it.
     *
     * <p>First every chunk of the island says which creatures it holds, by their own id and by the mark
     * a brought back one carries. Only then is anything spawned, so a creature that wandered into the
     * next chunk still counts as there.
     */
    static void spawnMissing(
            World world, IslandBounds bounds, List<Captured> captured, @Nullable SchedulerPort scheduler) {
        if (captured.isEmpty()) {
            return;
        }
        Set<UUID> present = ConcurrentHashMap.newKeySet();
        List<CompletableFuture<Void>> looked = new ArrayList<>();
        for (int cx = bounds.minX() >> 4; cx <= bounds.maxX() >> 4; cx++) {
            for (int cz = bounds.minZ() >> 4; cz <= bounds.maxZ() >> 4; cz++) {
                int chunkX = cx;
                int chunkZ = cz;
                looked.add(inRegion(world, chunkX, chunkZ, scheduler, () -> {
                    for (Entity entity : world.getChunkAt(chunkX, chunkZ).getEntities()) {
                        present.add(entity.getUniqueId());
                        String mark = entity.getPersistentDataContainer().get(ORIGINAL, PersistentDataType.STRING);
                        if (mark != null) {
                            present.add(UUID.fromString(mark));
                        }
                    }
                }));
            }
        }
        CompletableFuture.allOf(looked.toArray(CompletableFuture<?>[]::new)).join();

        Map<Long, List<Captured>> byChunk = new HashMap<>();
        for (Captured entity : captured) {
            if (!present.contains(entity.id())) {
                long key = (((long) ((int) Math.floor(entity.x()) >> 4)) << 32)
                        | (((int) Math.floor(entity.z()) >> 4) & 0xFFFFFFFFL);
                byChunk.computeIfAbsent(key, k -> new ArrayList<>()).add(entity);
            }
        }
        List<CompletableFuture<Void>> spawned = new ArrayList<>();
        for (Map.Entry<Long, List<Captured>> chunk : byChunk.entrySet()) {
            int chunkX = (int) (chunk.getKey() >> 32);
            int chunkZ = (int) (long) chunk.getKey();
            spawned.add(inRegion(world, chunkX, chunkZ, scheduler, () -> {
                for (Captured entity : chunk.getValue()) {
                    spawn(world, entity);
                }
            }));
        }
        CompletableFuture.allOf(spawned.toArray(CompletableFuture<?>[]::new)).join();
    }

    private static void spawn(World world, Captured entity) {
        NamespacedKey key = NamespacedKey.fromString(entity.type());
        EntityType type = key == null ? null : Registry.ENTITY_TYPE.get(key);
        if (type == null || !type.isSpawnable()) {
            return;
        }
        Entity back = world.spawnEntity(
                new Location(world, entity.x(), entity.y(), entity.z(), entity.yaw(), entity.pitch()), type);
        back.getPersistentDataContainer()
                .set(ORIGINAL, PersistentDataType.STRING, entity.id().toString());
    }

    private static CompletableFuture<Void> inRegion(
            World world, int chunkX, int chunkZ, @Nullable SchedulerPort scheduler, Runnable work) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        Runnable task = () -> {
            try {
                work.run();
                done.complete(null);
            } catch (RuntimeException failed) {
                done.completeExceptionally(failed);
            }
        };
        if (scheduler == null) {
            task.run();
        } else {
            scheduler.onRegion(world.getName(), chunkX, chunkZ, task);
        }
        return done;
    }

    private static boolean inside(IslandBounds bounds, int x, int z) {
        return x >= bounds.minX() && x <= bounds.maxX() && z >= bounds.minZ() && z <= bounds.maxZ();
    }
}
