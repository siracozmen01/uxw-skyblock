package com.uxplima.uxmskyblock.bukkit.config;

import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

import org.spongepowered.configurate.ConfigurationNode;

/**
 * {@code modules/strangerrealms.conf}: whether islands can be StrangerRealms islands, how the Upside
 * Down mirrors their land, and what it makes of the creatures born in it.
 */
public record StrangerRealmsConfiguration(boolean enabled, UpsideDown upsideDown, Mobs mobs, Glimmer glimmer) {

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

    /**
     * The glimmer: a light set on a StrangerRealms island's land shines through at the same place in
     * the Upside Down, and the other way round, while it stands.
     *
     * @param enabled whether lights glimmer across at all
     * @param lights the blocks that are lights, each a name or a name with one {@code *}
     * @param level how bright the glimmer is, from 1 to 15
     */
    public record Glimmer(boolean enabled, List<String> lights, int level) {

        public static final Glimmer SHIPPED = new Glimmer(
                true,
                List.of(
                        "TORCH",
                        "WALL_TORCH",
                        "SOUL_TORCH",
                        "SOUL_WALL_TORCH",
                        "LANTERN",
                        "SOUL_LANTERN",
                        "GLOWSTONE",
                        "SEA_LANTERN",
                        "SHROOMLIGHT",
                        "JACK_O_LANTERN",
                        "*_CANDLE",
                        "CANDLE",
                        "REDSTONE_LAMP"),
                12);

        public Glimmer {
            lights = List.copyOf(lights);
            if (level < 1 || level > 15) {
                throw new IllegalArgumentException("the level is from 1 to 15");
            }
        }
    }

    public StrangerRealmsConfiguration {
        Objects.requireNonNull(upsideDown, "upsideDown must not be null");
        Objects.requireNonNull(mobs, "mobs must not be null");
        Objects.requireNonNull(glimmer, "glimmer must not be null");
    }

    public static StrangerRealmsConfiguration defaultConfiguration() {
        return new StrangerRealmsConfiguration(true, UpsideDown.SHIPPED, Mobs.SHIPPED, Glimmer.SHIPPED);
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
        ConfigurationNode written = root.node("glimmer");
        Glimmer glimmer;
        try {
            glimmer = new Glimmer(
                    written.node("enabled").getBoolean(Glimmer.SHIPPED.enabled()),
                    AcidIslandConfiguration.strings(written.node("lights"), Glimmer.SHIPPED.lights()),
                    written.node("level").getInt(Glimmer.SHIPPED.level()));
        } catch (IllegalArgumentException e) {
            LOGGER.warning(
                    () -> "modules/strangerrealms.conf glimmer: " + e.getMessage() + ". The shipped glimmer is used.");
            glimmer = Glimmer.SHIPPED;
        }
        return new StrangerRealmsConfiguration(
                root.node("enabled").getBoolean(true),
                upsideDown,
                new Mobs(
                        AcidIslandConfiguration.strings(mobs.node("reasons"), Mobs.SHIPPED.reasons()),
                        AcidIslandConfiguration.strings(mobs.node("turn"), Mobs.SHIPPED.turn()),
                        AcidIslandConfiguration.strings(mobs.node("effects"), Mobs.SHIPPED.effects())),
                glimmer);
    }
}
