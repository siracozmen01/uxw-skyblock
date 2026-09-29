package com.uxplima.uxmskyblock.bukkit.config;

import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

import org.spongepowered.configurate.ConfigurationNode;

/**
 * {@code modules/strangerrealms.conf}: whether islands can be StrangerRealms islands, and how the Upside
 * Down mirrors their land.
 */
public record StrangerRealmsConfiguration(boolean enabled, UpsideDown upsideDown) {

    private static final Logger LOGGER = Logger.getLogger(StrangerRealmsConfiguration.class.getName());

    /**
     * The Upside Down, which takes the place of the Nether and mirrors the island's land.
     *
     * @param radius how far from the island's centre the land is mirrored, in blocks
     * @param palette how the mirror distresses what it copies, each rule written {@code FROM:TO}
     */
    public record UpsideDown(int radius, List<String> palette) {

        public static final UpsideDown SHIPPED = new UpsideDown(
                48,
                List.of(
                        "GRASS_BLOCK:MYCELIUM",
                        "DIRT:COARSE_DIRT",
                        "DIRT_PATH:COARSE_DIRT",
                        "SHORT_GRASS:DEAD_BUSH",
                        "FERN:DEAD_BUSH",
                        "TALL_GRASS:AIR",
                        "LARGE_FERN:AIR",
                        "*_LEAVES:AIR",
                        "*_LOG:STRIPPED_DARK_OAK_LOG",
                        "*_WOOD:STRIPPED_DARK_OAK_WOOD",
                        "POPPY:WITHER_ROSE",
                        "DANDELION:WITHER_ROSE",
                        "SAND:SOUL_SAND"));

        public UpsideDown {
            palette = List.copyOf(palette);
            if (radius < 0) {
                throw new IllegalArgumentException("the radius must not be negative");
            }
        }
    }

    public StrangerRealmsConfiguration {
        Objects.requireNonNull(upsideDown, "upsideDown must not be null");
    }

    public static StrangerRealmsConfiguration defaultConfiguration() {
        return new StrangerRealmsConfiguration(true, UpsideDown.SHIPPED);
    }

    public static StrangerRealmsConfiguration load(ConfigurationNode root) {
        ConfigurationNode node = root.node("upside-down");
        UpsideDown upsideDown;
        try {
            upsideDown = new UpsideDown(
                    node.node("radius").getInt(UpsideDown.SHIPPED.radius()),
                    AcidIslandConfiguration.strings(node.node("palette"), UpsideDown.SHIPPED.palette()));
        } catch (IllegalArgumentException e) {
            LOGGER.warning(() -> "modules/strangerrealms.conf upside-down: " + e.getMessage()
                    + ". The shipped Upside Down is used.");
            upsideDown = UpsideDown.SHIPPED;
        }
        return new StrangerRealmsConfiguration(root.node("enabled").getBoolean(true), upsideDown);
    }
}
