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

    private final Set<String> suppressedStructures;

    public WorldConfiguration(Set<String> suppressedStructures) {
        this.suppressedStructures = Collections.unmodifiableSet(new HashSet<>(suppressedStructures));
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

        try {
            List<String> list = rootNode.node("suppressed-structures").getList(String.class);
            if (list == null || list.isEmpty()) {
                return defaultConfiguration();
            }
            return new WorldConfiguration(new HashSet<>(list));
        } catch (org.spongepowered.configurate.serialize.SerializationException e) {
            return defaultConfiguration();
        }
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
