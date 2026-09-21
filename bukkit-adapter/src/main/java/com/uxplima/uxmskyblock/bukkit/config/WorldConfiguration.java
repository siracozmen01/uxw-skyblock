package com.uxplima.uxmskyblock.bukkit.config;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.spongepowered.configurate.ConfigurationNode;

/**
 * Void-world optimization and structure suppression configuration (Section 2.42).
 */
public final class WorldConfiguration {

    /** The height a new island's spawn sits at, when the file names none. */
    public static final int DEFAULT_ISLAND_SPAWN_Y = 100;

    private final Set<String> suppressedStructures;
    private final int islandSpawnY;

    public WorldConfiguration(Set<String> suppressedStructures) {
        this(suppressedStructures, DEFAULT_ISLAND_SPAWN_Y);
    }

    public WorldConfiguration(Set<String> suppressedStructures, int islandSpawnY) {
        this.suppressedStructures = Collections.unmodifiableSet(new HashSet<>(suppressedStructures));
        this.islandSpawnY = islandSpawnY;
    }

    public static WorldConfiguration defaultConfiguration() {
        return new WorldConfiguration(Set.of(
                "minecraft:mansion",
                "minecraft:monument",
                "minecraft:buried_treasure",
                "minecraft:trial_chambers",
                "minecraft:pillager_outpost",
                "minecraft:ancient_city"));
    }

    public static WorldConfiguration load(ConfigurationNode rootNode) {
        Objects.requireNonNull(rootNode, "rootNode must not be null");
        if (rootNode.virtual() || rootNode.empty()) {
            return defaultConfiguration();
        }

        int spawnY = rootNode.node("island-spawn-y").getInt(DEFAULT_ISLAND_SPAWN_Y);

        try {
            List<String> list = rootNode.node("suppressed-structures").getList(String.class);
            if (list == null || list.isEmpty()) {
                return new WorldConfiguration(defaultConfiguration().suppressedStructures(), spawnY);
            }
            return new WorldConfiguration(new HashSet<>(list), spawnY);
        } catch (org.spongepowered.configurate.serialize.SerializationException e) {
            return new WorldConfiguration(defaultConfiguration().suppressedStructures(), spawnY);
        }
    }

    /**
     * How high above the void a new island's spawn sits.
     *
     * <p>It is the height the starter schematic is pasted at, so an operator who builds their
     * starter island at a different height moves this with it rather than asking for a release.
     */
    public int islandSpawnY() {
        return islandSpawnY;
    }

    public boolean isSuppressed(String structureKey) {
        if (structureKey == null) {
            return false;
        }
        return suppressedStructures.contains(
                structureKey.toLowerCase(java.util.Locale.ROOT).trim());
    }

    public Set<String> suppressedStructures() {
        return suppressedStructures;
    }
}
