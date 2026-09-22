package com.uxplima.uxmskyblock.bukkit.config;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.biome.IslandBiome;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Which biomes an island may take, and what it has to reach first.
 *
 * <p>The unlock levels were written into the enum, so an operator who wanted the end at level ten
 * rather than fifty had nowhere to say so. The enum still carries the number this plugin ships
 * with, and this file is what the operator changes.
 *
 * <p>A biome the file switches off is refused whatever the level is. A biome the file does not
 * mention keeps the shipped number, so adding a biome to the plugin does not silently unlock it on
 * a server whose file was written before it existed.
 */
public record BiomeConfiguration(
        boolean enabled, Map<IslandBiome, Integer> requiredLevels, Map<IslandBiome, Boolean> offered) {

    public static final boolean DEFAULT_ENABLED = true;

    public BiomeConfiguration {
        Objects.requireNonNull(requiredLevels, "requiredLevels must not be null");
        Objects.requireNonNull(offered, "offered must not be null");
        requiredLevels = Map.copyOf(requiredLevels);
        offered = Map.copyOf(offered);
    }

    /** What this plugin ships with: every biome offered, at the level its own definition names. */
    public static BiomeConfiguration defaultConfiguration() {
        Map<IslandBiome, Integer> levels = new LinkedHashMap<>();
        Map<IslandBiome, Boolean> offered = new LinkedHashMap<>();
        for (IslandBiome biome : IslandBiome.values()) {
            levels.put(biome, biome.requiredLevel());
            offered.put(biome, true);
        }
        return new BiomeConfiguration(DEFAULT_ENABLED, levels, offered);
    }

    /** The level an island must reach before it may take this biome. */
    public int requiredLevelOf(IslandBiome biome) {
        Objects.requireNonNull(biome, "biome must not be null");
        return requiredLevels.getOrDefault(biome, biome.requiredLevel());
    }

    /** Whether this server offers this biome at all. */
    public boolean offers(IslandBiome biome) {
        Objects.requireNonNull(biome, "biome must not be null");
        return offered.getOrDefault(biome, true);
    }

    public static BiomeConfiguration load(ConfigurationNode rootNode) {
        Objects.requireNonNull(rootNode, "rootNode must not be null");
        ConfigurationNode node = rootNode.node("biomes");
        if (node.virtual() || node.empty()) {
            return defaultConfiguration();
        }

        boolean enabled = node.node("enabled").getBoolean(DEFAULT_ENABLED);
        Map<IslandBiome, Integer> levels = new LinkedHashMap<>();
        Map<IslandBiome, Boolean> offered = new LinkedHashMap<>();
        ConfigurationNode list = node.node("unlocks");
        for (IslandBiome biome : IslandBiome.values()) {
            ConfigurationNode entry = list.node(biome.id());
            levels.put(biome, Math.max(0, entry.node("required-level").getInt(biome.requiredLevel())));
            offered.put(biome, entry.node("offered").getBoolean(true));
        }
        return new BiomeConfiguration(enabled, levels, offered);
    }

    /** The biomes this server offers, in the order they are defined, for a refusal that lists them. */
    public String offeredNames() {
        return Arrays.stream(IslandBiome.values())
                .filter(this::offers)
                .map(biome -> biome.id().toLowerCase(Locale.ROOT))
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    }
}
