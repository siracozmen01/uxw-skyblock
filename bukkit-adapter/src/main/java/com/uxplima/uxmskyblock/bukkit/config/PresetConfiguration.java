package com.uxplima.uxmskyblock.bukkit.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.application.preset.StarterPresetCatalog;
import com.uxplima.uxmskyblock.core.domain.biome.IslandBiome;
import com.uxplima.uxmskyblock.core.domain.preset.StarterPreset;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * The starter presets an operator's file describes.
 *
 * <p>The four the plugin ships with were written in Java and there could never be a fifth. They are
 * the fallback now, and {@code modules/presets.conf} is where a server says what it offers.
 */
public record PresetConfiguration(List<StarterPreset> presets, String defaultId) {

    public PresetConfiguration {
        Objects.requireNonNull(presets, "presets must not be null");
        Objects.requireNonNull(defaultId, "defaultId must not be null");
        presets = List.copyOf(presets);
    }

    public static PresetConfiguration defaultConfiguration() {
        return new PresetConfiguration(StarterPresetCatalog.shipped(), StarterPresetCatalog.CLASSIC.id());
    }

    public static PresetConfiguration load(ConfigurationNode rootNode) {
        Objects.requireNonNull(rootNode, "rootNode must not be null");
        ConfigurationNode node = rootNode.node("presets");
        ConfigurationNode entries = node.node("entries");
        if (node.virtual() || entries.virtual() || !entries.isMap()) {
            return defaultConfiguration();
        }

        List<StarterPreset> presets = new ArrayList<>();
        for (var entry : entries.childrenMap().entrySet()) {
            String id = String.valueOf(entry.getKey()).trim();
            if (id.isEmpty()) {
                continue;
            }
            ConfigurationNode preset = entry.getValue();
            String displayName = preset.node("display-name").getString("@presets." + id + ".name");
            String description = preset.node("description").getString("@presets." + id + ".description");
            String schematic = preset.node("schematic").getString("schematics/" + id + ".schem");
            presets.add(new StarterPreset(id, displayName, description, schematic, biomeOf(preset)));
        }

        if (presets.isEmpty()) {
            return defaultConfiguration();
        }
        String defaultId = node.node("default").getString(presets.get(0).id());
        return new PresetConfiguration(presets, defaultId);
    }

    /** An operator who writes a biome the server does not know gets plains rather than a failed start. */
    private static IslandBiome biomeOf(ConfigurationNode preset) {
        String written = preset.node("biome").getString("PLAINS");
        try {
            return IslandBiome.valueOf(written.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return IslandBiome.PLAINS;
        }
    }

    public StarterPresetCatalog catalogue() {
        return new StarterPresetCatalog(presets, defaultId);
    }
}
