package com.uxplima.uxmskyblock.bukkit.world;

import java.util.Objects;
import java.util.Optional;

import org.bukkit.World;
import org.bukkit.generator.ChunkGenerator;

import org.jspecify.annotations.Nullable;

/**
 * What an operator is told at startup about the world the islands live in.
 *
 * <p>Nothing said so before: a server that installed the plugin placed every island into ordinary
 * terrain and the console was silent about it. This says it once, at startup.
 */
public final class IslandWorldCheck {

    private IslandWorldCheck() {}

    /** The line to log, or nothing when the island world is loaded and empty. */
    public static Optional<String> warningFor(String worldName, @Nullable World world, String pluginName) {
        Objects.requireNonNull(worldName, "worldName must not be null");
        Objects.requireNonNull(pluginName, "pluginName must not be null");
        if (world == null) {
            return Optional.of("The island world '" + worldName + "' is not loaded, so no island can be made."
                    + " Name a loaded world as server-node.world-name in config.conf.");
        }
        ChunkGenerator generator = world.getGenerator();
        if (!(generator instanceof VoidIslandGenerator)) {
            return Optional.of("The island world '" + worldName + "' is generated terrain, so islands will be"
                    + " placed into hills and caves. An empty world made by the " + pluginName
                    + " generator is what an island is meant to float in.");
        }
        return Optional.empty();
    }
}
