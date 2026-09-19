package com.uxplima.uxmskyblock.bukkit.config;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.dimension.DimensionMapping;
import com.uxplima.uxmskyblock.core.domain.dimension.DimensionMode;
import com.uxplima.uxmskyblock.core.domain.dimension.IslandDimensionType;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Configuration holder for multi-dimension architecture, portal routing, and platform schematics (Sections 2.27 & 2.37).
 */
public record DimensionConfiguration(boolean enabled, Map<IslandDimensionType, DimensionMapping> mappings) {

    public static final boolean DEFAULT_ENABLED = true;
    public static final String DEFAULT_NETHER_WORLD = "skyblock_nether";
    public static final String DEFAULT_END_WORLD = "skyblock_the_end";
    public static final String DEFAULT_NETHER_UPGRADE = "island_nether";
    public static final String DEFAULT_END_UPGRADE = "island_end";
    public static final String DEFAULT_NETHER_SCHEMATIC = "island_nether";
    public static final String DEFAULT_END_SCHEMATIC = "island_end";

    public DimensionConfiguration {
        Objects.requireNonNull(mappings, "mappings must not be null");
        EnumMap<IslandDimensionType, DimensionMapping> copy = new EnumMap<>(IslandDimensionType.class);
        copy.putAll(mappings);
        mappings = Collections.unmodifiableMap(copy);
    }

    public static DimensionConfiguration defaultConfiguration() {
        Map<IslandDimensionType, DimensionMapping> defaultMappings = Map.of(
                IslandDimensionType.OVERWORLD,
                new DimensionMapping(
                        IslandDimensionType.OVERWORLD, DimensionMode.PRIVATE_ISLAND, "skyblock_world", null, null),
                IslandDimensionType.NETHER,
                new DimensionMapping(
                        IslandDimensionType.NETHER,
                        DimensionMode.PRIVATE_ISLAND,
                        DEFAULT_NETHER_WORLD,
                        new UpgradeId(DEFAULT_NETHER_UPGRADE),
                        DEFAULT_NETHER_SCHEMATIC),
                IslandDimensionType.THE_END,
                new DimensionMapping(
                        IslandDimensionType.THE_END,
                        DimensionMode.PRIVATE_ISLAND,
                        DEFAULT_END_WORLD,
                        new UpgradeId(DEFAULT_END_UPGRADE),
                        DEFAULT_END_SCHEMATIC));

        return new DimensionConfiguration(DEFAULT_ENABLED, defaultMappings);
    }

    public static DimensionConfiguration load(ConfigurationNode rootNode) {
        Objects.requireNonNull(rootNode, "rootNode must not be null");
        ConfigurationNode node = rootNode.node("dimensions");
        if (node.virtual() || node.empty()) {
            node = rootNode;
        }

        boolean enabled = node.node("enabled").getBoolean(DEFAULT_ENABLED);

        EnumMap<IslandDimensionType, DimensionMapping> mappings = new EnumMap<>(IslandDimensionType.class);
        mappings.put(
                IslandDimensionType.OVERWORLD,
                new DimensionMapping(
                        IslandDimensionType.OVERWORLD, DimensionMode.PRIVATE_ISLAND, "skyblock_world", null, null));

        ConfigurationNode netherNode = node.node("nether");
        mappings.put(
                IslandDimensionType.NETHER,
                parseMapping(
                        netherNode,
                        IslandDimensionType.NETHER,
                        DEFAULT_NETHER_WORLD,
                        DEFAULT_NETHER_UPGRADE,
                        DEFAULT_NETHER_SCHEMATIC));

        ConfigurationNode endNode = node.node("end");
        mappings.put(
                IslandDimensionType.THE_END,
                parseMapping(
                        endNode,
                        IslandDimensionType.THE_END,
                        DEFAULT_END_WORLD,
                        DEFAULT_END_UPGRADE,
                        DEFAULT_END_SCHEMATIC));

        return new DimensionConfiguration(enabled, mappings);
    }

    private static DimensionMapping parseMapping(
            ConfigurationNode node,
            IslandDimensionType type,
            String defaultWorld,
            String defaultUpgrade,
            String defaultSchematic) {
        if (node.virtual() || node.empty()) {
            return new DimensionMapping(
                    type,
                    DimensionMode.PRIVATE_ISLAND,
                    defaultWorld,
                    defaultUpgrade != null ? new UpgradeId(defaultUpgrade) : null,
                    defaultSchematic);
        }

        String modeRaw = node.node("mode").getString("PRIVATE_ISLAND").trim().toUpperCase(Locale.ROOT);
        DimensionMode mode;
        try {
            mode = DimensionMode.valueOf(modeRaw);
        } catch (IllegalArgumentException ex) {
            mode = DimensionMode.PRIVATE_ISLAND;
        }

        String worldName = node.node("world-name").getString(defaultWorld);
        String upgradeRaw = node.node("required-upgrade").getString();
        UpgradeId upgradeId = (upgradeRaw != null && !upgradeRaw.isBlank()) ? new UpgradeId(upgradeRaw.trim()) : null;
        String schematic = node.node("schematic").getString(defaultSchematic);

        return new DimensionMapping(type, mode, worldName, upgradeId, schematic);
    }
}
