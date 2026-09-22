package com.uxplima.uxmskyblock.bukkit.world;

import java.util.Random;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.generator.ChunkGenerator;

/**
 * A world with nothing in it, for islands to float in.
 *
 * <p>The plugin shipped without one, so a server that installed it placed every island into the
 * ordinary generated world: a starter island pasted into a hillside, caves under it and a village
 * beside it. A world loader names it as {@code uxmSkyblock}. The server's own default world cannot
 * take it yet, because the server makes that world before this plugin enables.
 */
public final class VoidIslandGenerator extends ChunkGenerator {

    /** The id an operator writes after the plugin's name, or leaves out. */
    public static final String ID = "void";

    @Override
    public boolean shouldGenerateNoise() {
        return false;
    }

    @Override
    public boolean shouldGenerateSurface() {
        return false;
    }

    @Override
    public boolean shouldGenerateCaves() {
        return false;
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return false;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return false;
    }

    @Override
    public boolean shouldGenerateStructures() {
        return false;
    }

    /** Where a player lands who has no island yet: the middle of the world, above nothing. */
    @Override
    public Location getFixedSpawnLocation(World world, Random random) {
        return new Location(world, 0.5, 100, 0.5);
    }
}
