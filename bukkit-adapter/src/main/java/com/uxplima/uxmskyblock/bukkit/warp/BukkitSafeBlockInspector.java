package com.uxplima.uxmskyblock.bukkit.warp;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import com.uxplima.uxmskyblock.core.application.warp.SafeBlockInspector;

/**
 * Bukkit/Paper adapter for {@link SafeBlockInspector} examining block materials, passability,
 * and environmental hazards (lava, fire, cactus, wither rose, void) within world contexts.
 */
public final class BukkitSafeBlockInspector implements SafeBlockInspector {

    @Override
    public boolean isSolidFloor(String worldName, int x, int y, int z) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return false;
        }
        Block block = world.getBlockAt(x, y, z);
        Material mat = block.getType();
        return mat.isSolid() && !isHazardousMaterial(mat);
    }

    @Override
    public boolean isPassable(String worldName, int x, int y, int z) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return false;
        }
        Block block = world.getBlockAt(x, y, z);
        return block.isPassable() && !isHazardousMaterial(block.getType());
    }

    @Override
    public boolean isHazardous(String worldName, int x, int y, int z) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return true;
        }
        Block block = world.getBlockAt(x, y, z);
        return isHazardousMaterial(block.getType());
    }

    @Override
    public boolean isWithinWorldBounds(String worldName, int y) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return y >= -64 && y <= 320;
        }
        return y >= world.getMinHeight() && y < world.getMaxHeight();
    }

    private boolean isHazardousMaterial(Material mat) {
        return switch (mat) {
            case LAVA,
                    FIRE,
                    SOUL_FIRE,
                    CAMPFIRE,
                    SOUL_CAMPFIRE,
                    CACTUS,
                    WITHER_ROSE,
                    SWEET_BERRY_BUSH,
                    MAGMA_BLOCK,
                    POWDER_SNOW -> true;
            default -> false;
        };
    }
}
