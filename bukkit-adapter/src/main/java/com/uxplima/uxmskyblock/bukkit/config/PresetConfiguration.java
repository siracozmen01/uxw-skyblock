package com.uxplima.uxmskyblock.bukkit.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;

import com.uxplima.uxmskyblock.core.application.preset.StarterPresetCatalog;
import com.uxplima.uxmskyblock.core.domain.biome.IslandBiome;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType;
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
            presets.add(new StarterPreset(
                    id, displayName, description, schematic, biomeOf(preset), modeOf(preset), startOf(preset)));
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

    /** The game mode the island plays. One the plugin does not know is a skyblock island. */
    private static GameModeType modeOf(ConfigurationNode preset) {
        String written = preset.node("mode").getString("SKYBLOCK");
        try {
            return GameModeType.valueOf(written.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return GameModeType.SKYBLOCK;
        }
    }

    /** The creation actions that build the island, in order; the starter platform when none is written. */
    private static List<String> startOf(ConfigurationNode preset) {
        List<String> start = new ArrayList<>();
        for (ConfigurationNode action : preset.node("start").childrenList()) {
            String written = action.getString("").trim();
            if (!written.isEmpty()) {
                start.add(written);
            }
        }
        return start.isEmpty() ? List.of(StarterPreset.PLATFORM) : start;
    }

    /**
     * The presets whose every creation action has a provider on this server. A preset of a game mode the
     * operator switched off names an action nobody provides, and is not offered.
     */
    public PresetConfiguration startableWith(Predicate<List<String>> provided) {
        List<StarterPreset> startable =
                presets.stream().filter(preset -> provided.test(preset.start())).toList();
        if (startable.isEmpty()) {
            return defaultConfiguration();
        }
        return new PresetConfiguration(startable, defaultId);
    }

    public StarterPresetCatalog catalogue() {
        return new StarterPresetCatalog(presets, defaultId);
    }
}
