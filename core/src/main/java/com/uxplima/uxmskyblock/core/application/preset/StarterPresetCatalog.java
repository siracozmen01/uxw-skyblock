package com.uxplima.uxmskyblock.core.application.preset;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.biome.IslandBiome;
import com.uxplima.uxmskyblock.core.domain.preset.StarterPreset;
import org.jspecify.annotations.Nullable;

/**
 * The starter islands a player may choose between.
 *
 * <p>The four below were the only four there could ever be: they were written here, in Java, with
 * their names, their descriptions and their schematic paths, and an operator who wanted a fifth, or
 * who built their own classic island and wanted it pasted instead, had to ask for a release. They
 * are what this catalogue holds when the operator's file names nothing, and nothing more.
 *
 * <p>A display name and a description are sentences a player reads, so neither is written here
 * either. Both are catalogue keys, and every language file answers them.
 */
public final class StarterPresetCatalog {

    public static final StarterPreset CLASSIC = new StarterPreset(
            "classic",
            "@presets.classic.name",
            "@presets.classic.description",
            "schematics/classic.schem",
            IslandBiome.PLAINS);

    public static final StarterPreset DESERT = new StarterPreset(
            "desert",
            "@presets.desert.name",
            "@presets.desert.description",
            "schematics/desert.schem",
            IslandBiome.DESERT);

    public static final StarterPreset NETHER = new StarterPreset(
            "nether",
            "@presets.nether.name",
            "@presets.nether.description",
            "schematics/nether.schem",
            IslandBiome.NETHER_WASTES);

    public static final StarterPreset CAVE = new StarterPreset(
            "cave", "@presets.cave.name", "@presets.cave.description", "schematics/cave.schem", IslandBiome.PLAINS);

    /** What a server ships with when its file names no preset of its own. */
    public static List<StarterPreset> shipped() {
        return List.of(CLASSIC, DESERT, NETHER, CAVE);
    }

    private final Map<String, StarterPreset> presets;
    private final StarterPreset defaultPreset;

    public StarterPresetCatalog() {
        this(shipped(), CLASSIC.id());
    }

    /**
     * The catalogue an operator's file describes.
     *
     * @param presets every preset, in the order the file lists them
     * @param defaultId the preset a player who names none gets; the first one when it names no such preset
     */
    public StarterPresetCatalog(List<StarterPreset> presets, String defaultId) {
        Objects.requireNonNull(presets, "presets must not be null");
        Objects.requireNonNull(defaultId, "defaultId must not be null");
        if (presets.isEmpty()) {
            throw new IllegalArgumentException("A server with no starter preset has no way to make an island");
        }
        Map<String, StarterPreset> map = new LinkedHashMap<>();
        for (StarterPreset preset : presets) {
            map.put(key(preset.id()), preset);
        }
        this.presets = Collections.unmodifiableMap(map);
        StarterPreset named = map.get(key(defaultId));
        this.defaultPreset = named != null ? named : presets.get(0);
    }

    public List<StarterPreset> allPresets() {
        return List.copyOf(presets.values());
    }

    public Optional<StarterPreset> findById(@Nullable String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(presets.get(key(id)));
    }

    public StarterPreset defaultPreset() {
        return defaultPreset;
    }

    private static String key(String id) {
        return id.toLowerCase(Locale.ROOT).trim();
    }
}
