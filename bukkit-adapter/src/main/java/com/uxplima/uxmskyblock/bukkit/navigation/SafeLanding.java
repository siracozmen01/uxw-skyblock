package com.uxplima.uxmskyblock.bukkit.navigation;

import java.util.Objects;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Where a player arriving at an island's spawn can stand.
 *
 * <p>A spawn is stored once and the island grows around it: the classic island's sapling stood on
 * the centre and became a tree, and a player sent home arrived inside its trunk and suffocated. A
 * spawn whose feet or head would be in a solid block is raised to the first two open blocks above it.
 *
 * <p>It reads blocks, so on Folia it runs on the thread that owns the destination.
 */
public final class SafeLanding {

    /** How far above a buried spawn to look before giving up and using the spawn as it is. */
    static final int MAX_RISE = 16;

    private SafeLanding() {}

    /** {@code at}, or the nearest spot straight above it with room for a player. */
    public static Location clear(Location at) {
        Objects.requireNonNull(at, "at");
        World world = Objects.requireNonNull(at.getWorld(), "at has no world");
        int x = at.getBlockX();
        int z = at.getBlockZ();
        int feet = at.getBlockY();
        for (int rise = 0; rise <= MAX_RISE; rise++) {
            if (open(world.getBlockAt(x, feet + rise, z)) && open(world.getBlockAt(x, feet + rise + 1, z))) {
                if (rise == 0) {
                    return at;
                }
                Location raised = at.clone();
                raised.setY(feet + rise);
                return raised;
            }
        }
        return at;
    }

    private static boolean open(Block block) {
        return !block.getType().isSolid();
    }
}
