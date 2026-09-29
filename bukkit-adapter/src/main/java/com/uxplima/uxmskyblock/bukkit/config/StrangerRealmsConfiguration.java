package com.uxplima.uxmskyblock.bukkit.config;

import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

import org.spongepowered.configurate.ConfigurationNode;

/**
 * {@code modules/strangerrealms.conf}: whether islands can be StrangerRealms islands, how the Upside
 * Down mirrors their land, and what it makes of the creatures born in it.
 */
public record StrangerRealmsConfiguration(boolean enabled, UpsideDown upsideDown, Mobs mobs) {

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

    /**
     * What the Upside Down makes of a creature born in it, on a StrangerRealms island's land.
     *
     * @param reasons the ways of being born that are turned, such as {@code NATURAL} or {@code SPAWNER}
     * @param turn each rule written {@code FROM:TO}, a creature or a name with one {@code *}, turned into
     *     another creature, or into {@code NONE} to keep it from being born at all
     * @param effects what every creature born there carries, each written {@code name:amplifier:seconds}
     */
    public record Mobs(List<String> reasons, List<String> turn, List<String> effects) {

        public static final Mobs SHIPPED = new Mobs(
                List.of("NATURAL", "CHUNK_GEN", "SPAWNER"),
                List.of(
                        "PIGLIN:ZOMBIE",
                        "PIGLIN_BRUTE:VINDICATOR",
                        "ZOMBIFIED_PIGLIN:HUSK",
                        "HOGLIN:ZOGLIN",
                        "MAGMA_CUBE:SLIME",
                        "GHAST:PHANTOM",
                        "WITHER_SKELETON:STRAY",
                        "BLAZE:VEX",
                        "STRIDER:NONE"),
                List.of("speed:0:3600", "resistance:0:3600"));

        public Mobs {
            reasons = List.copyOf(reasons);
            turn = List.copyOf(turn);
            effects = List.copyOf(effects);
        }
    }

    public StrangerRealmsConfiguration {
        Objects.requireNonNull(upsideDown, "upsideDown must not be null");
        Objects.requireNonNull(mobs, "mobs must not be null");
    }

    public static StrangerRealmsConfiguration defaultConfiguration() {
        return new StrangerRealmsConfiguration(true, UpsideDown.SHIPPED, Mobs.SHIPPED);
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
        ConfigurationNode mobs = root.node("mobs");
        return new StrangerRealmsConfiguration(
                root.node("enabled").getBoolean(true),
                upsideDown,
                new Mobs(
                        AcidIslandConfiguration.strings(mobs.node("reasons"), Mobs.SHIPPED.reasons()),
                        AcidIslandConfiguration.strings(mobs.node("turn"), Mobs.SHIPPED.turn()),
                        AcidIslandConfiguration.strings(mobs.node("effects"), Mobs.SHIPPED.effects())));
    }
}
