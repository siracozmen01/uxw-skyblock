package com.uxplima.uxmskyblock.bukkit.schematic;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import com.uxplima.uxmlib.schematic.Schematic;
import com.uxplima.uxmlib.schematic.Vec3i;
import com.uxplima.uxmlib.schematic.paper.Rotation;
import org.jspecify.annotations.Nullable;

/**
 * Ground under an island world's spawn, where a player with no island stands.
 *
 * <p>An island world is void, and its spawn was the middle of it, above nothing. A player with no island,
 * one who had just reset theirs, was sent there, fell, was caught by the void guard and sent there again,
 * for as long as they stayed. Where nothing stands under the spawn this lays the ground the operator's
 * {@code schematics/world_spawn.schem} describes, a plain platform the plugin writes on its first start,
 * which an operator replaces with a lobby of their own. Ground that is already there is left alone.
 */
public final class SpawnGround {

    /** The data folder path of the ground's schematic. */
    public static final String FILE = IslandSchematics.FOLDER + "/world_spawn" + IslandSchematics.EXTENSION;

    /** How far under the spawn a block counts as ground a player lands on. */
    static final int DEPTH = 24;

    private static final int REACH = 3;
    private static final Logger LOGGER = Logger.getLogger(SpawnGround.class.getName());

    private final @Nullable IslandSchematics schematics;

    public SpawnGround(@Nullable IslandSchematics schematics) {
        this.schematics = schematics;
    }

    /** The shipped ground as a schematic saved around its middle, the block a player stands on. */
    public static Schematic shipped(int dataVersion) {
        int side = 2 * REACH + 1;
        Schematic.Builder builder = Schematic.builder(side, 1, side)
                .offset(new Vec3i(-REACH, 0, -REACH))
                .dataVersion(dataVersion);
        String stone = Material.SMOOTH_STONE.createBlockData().getAsString();
        for (int x = 0; x < side; x++) {
            for (int z = 0; z < side; z++) {
                builder.block(x, 0, z, stone);
            }
        }
        return builder.build();
    }

    /** Whether a player sent to {@code spawn} lands on something. */
    public static boolean hasGround(World world, Location spawn) {
        int x = spawn.getBlockX();
        int z = spawn.getBlockZ();
        int top = spawn.getBlockY() - 1;
        for (int y = top; y >= Math.max(world.getMinHeight(), top - DEPTH); y--) {
            if (world.getBlockAt(x, y, z).getType().isSolid()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Lays the ground under the world's spawn when nothing stands there. Called on the thread that owns the
     * spawn; a ground laid block by block after the file failed is handed back there through {@code region}.
     * Answers whether it laid any.
     */
    public CompletableFuture<Boolean> ensure(World world, Executor region) {
        Objects.requireNonNull(world, "world must not be null");
        Location spawn = world.getSpawnLocation();
        if (hasGround(world, spawn)) {
            return CompletableFuture.completedFuture(false);
        }
        Location under = new Location(world, spawn.getBlockX(), spawn.getBlockY() - 1, spawn.getBlockZ());
        IslandSchematics files = schematics;
        if (files == null) {
            lay(under);
            return CompletableFuture.completedFuture(true);
        }
        return files.read(FILE)
                .thenCompose(found -> found.isPresent()
                        ? files.paste(found.get(), under, Rotation.NONE).thenApply(report -> true)
                        : CompletableFuture.<Boolean>failedFuture(new IllegalStateException(FILE + " is not there")))
                .exceptionallyCompose(failure -> {
                    LOGGER.log(
                            Level.WARNING,
                            "The spawn ground of " + world.getName() + " was laid plain: " + failure.getMessage(),
                            failure);
                    return CompletableFuture.supplyAsync(
                            () -> {
                                lay(under);
                                return true;
                            },
                            region);
                });
    }

    /** The shipped ground, block by block, with its middle on {@code under}. */
    static void lay(Location under) {
        World world = Objects.requireNonNull(under.getWorld(), "under must be in a world");
        for (int dx = -REACH; dx <= REACH; dx++) {
            for (int dz = -REACH; dz <= REACH; dz++) {
                world.getBlockAt(under.getBlockX() + dx, under.getBlockY(), under.getBlockZ() + dz)
                        .setType(Material.SMOOTH_STONE);
            }
        }
    }
}
