package com.uxplima.uxmskyblock.core.application.preset;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.biome.IslandBiome;
import com.uxplima.uxmskyblock.core.domain.preset.StarterPreset;

/**
 * Registry catalog containing the standard starter presets available to new islands.
 */
public final class StarterPresetCatalog {

    public static final StarterPreset CLASSIC = new StarterPreset(
            "classic",
            "Classic Skyblock",
            "The traditional skyblock experience with an oak tree and essentials",
            "schematics/classic.schem",
            IslandBiome.PLAINS);

    public static final StarterPreset DESERT = new StarterPreset(
            "desert",
            "Desert Skyblock",
            "Arid desert island with cacti, dead bushes, and sand resources",
            "schematics/desert.schem",
            IslandBiome.DESERT);

    public static final StarterPreset NETHER = new StarterPreset(
            "nether",
            "Nether Skyblock",
            "Volcanic nether island with crimson vegetation and quartz veins",
            "schematics/nether.schem",
            IslandBiome.NETHER_WASTES);

    public static final StarterPreset CAVE = new StarterPreset(
            "cave",
            "Cave Skyblock",
            "Subterranean cavern island surrounded by stone, dripstone, and ores",
            "schematics/cave.schem",
            IslandBiome.PLAINS);

    private static final Map<String, StarterPreset> PRESETS;

    static {
        Map<String, StarterPreset> map = new LinkedHashMap<>();
        map.put(CLASSIC.id(), CLASSIC);
        map.put(DESERT.id(), DESERT);
        map.put(NETHER.id(), NETHER);
        map.put(CAVE.id(), CAVE);
        PRESETS = Collections.unmodifiableMap(map);
    }

    public List<StarterPreset> allPresets() {
        return List.copyOf(PRESETS.values());
    }

    public Optional<StarterPreset> findById(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(PRESETS.get(id.toLowerCase(Locale.ROOT).trim()));
    }

    public StarterPreset defaultPreset() {
        return CLASSIC;
    }
}
