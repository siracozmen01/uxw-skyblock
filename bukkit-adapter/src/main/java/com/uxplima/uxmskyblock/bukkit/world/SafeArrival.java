package com.uxplima.uxmskyblock.bukkit.world;

import java.util.OptionalInt;
import java.util.Set;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Where a player can arrive standing: a solid block under their feet that does not hurt, and room for
 * their feet and head that is neither solid nor liquid.
 *
 * <p>An island's spawn was one height for every island, the block above the platform. On land the
 * server made the usual way, a Boxed world or any world a preset names, that height is inside a hill
 * or high over a valley, and a player arrived suffocating or falling. The nearest height that is safe
 * is found in the column itself, up before down at each distance, so a player in a cave world arrives
 * in the cave rather than on its roof.
 *
 * <p>For a mode played under water, water is room like air, so a player on the sea floor arrives in the
 * sea rather than in a pocket of air cut out of it.
 */
public final class SafeArrival {

    /** How far up or down from the planned height a safe height is looked for. */
    public static final int REACH = 64;

    private static final Set<Material> HURTS = Set.of(
            Material.LAVA,
            Material.MAGMA_BLOCK,
            Material.CACTUS,
            Material.FIRE,
            Material.SOUL_FIRE,
            Material.CAMPFIRE,
            Material.SOUL_CAMPFIRE,
            Material.SWEET_BERRY_BUSH,
            Material.POWDER_SNOW,
            Material.POINTED_DRIPSTONE,
            Material.WITHER_ROSE);

    private SafeArrival() {
        throw new UnsupportedOperationException("SafeArrival is a rule about blocks, not a thing to hold");
    }

    /**
     * The height a player's feet can stand at in the column at {@code (x, z)}, nearest to {@code feet},
     * or empty when nothing within {@link #REACH} is safe. Read on the thread that owns the column.
     */
    public static OptionalInt standingY(World world, int x, int feet, int z) {
        return standingY(world, x, feet, z, false);
    }

    /** As {@link #standingY(World, int, int, int)}, with water counted as room when {@code underwater}. */
    public static OptionalInt standingY(World world, int x, int feet, int z, boolean underwater) {
        for (int distance = 0; distance <= REACH; distance++) {
            if (safe(world, x, feet + distance, z, underwater)) {
                return OptionalInt.of(feet + distance);
            }
            if (distance > 0 && safe(world, x, feet - distance, z, underwater)) {
                return OptionalInt.of(feet - distance);
            }
        }
        return OptionalInt.empty();
    }

    /**
     * Clears the room for a player's feet and head at {@code feet}, for a column where nothing within
     * reach is safe: rock all the way up and down, as deep in the Nether. The block under the feet is
     * left as the island made it. Read and written on the thread that owns the column.
     */
    public static void makeRoom(World world, int x, int feet, int z) {
        makeRoom(world, x, feet, z, false);
    }

    /** As {@link #makeRoom(World, int, int, int)}; under water the room is filled with water, not air. */
    public static void makeRoom(World world, int x, int feet, int z, boolean underwater) {
        for (int y = feet; y <= feet + 1; y++) {
            Block block = world.getBlockAt(x, y, z);
            if (!roomAt(block, underwater)) {
                block.setType(underwater ? Material.WATER : Material.AIR, false);
            }
        }
    }

    /** Whether a player's feet can stand at {@code y}. */
    public static boolean safe(World world, int x, int y, int z) {
        return safe(world, x, y, z, false);
    }

    /** As {@link #safe(World, int, int, int)}, with water counted as room when {@code underwater}. */
    public static boolean safe(World world, int x, int y, int z, boolean underwater) {
        // The logical height is where play stops: in the Nether, the bedrock roof. Above it is not a
        // place to arrive, though there is air and something solid under it.
        int top = world.getMinHeight() + world.getLogicalHeight();
        if (y - 1 < world.getMinHeight() || y + 1 >= Math.min(world.getMaxHeight(), top)) {
            return false;
        }
        Block ground = world.getBlockAt(x, y - 1, z);
        return ground.getType().isSolid()
                && !HURTS.contains(ground.getType())
                && roomAt(world.getBlockAt(x, y, z), underwater)
                && roomAt(world.getBlockAt(x, y + 1, z), underwater);
    }

    private static boolean roomAt(Block block, boolean underwater) {
        Material type = block.getType();
        if (type.isSolid() || HURTS.contains(type)) {
            return false;
        }
        return !block.isLiquid() || (underwater && type == Material.WATER);
    }
}
