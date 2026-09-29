package com.uxplima.uxmskyblock.bukkit.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.core.domain.hazard.AcidRules;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * {@code modules/acidisland.conf}: whether islands can be AcidIsland islands, the sea around them, how
 * much the sea and the rain hurt, and how water is made clean.
 *
 * @param waterProtection the effects, by name, that keep a player safe in the sea
 * @param waterEffects the effects the sea gives, each written {@code name:amplifier:seconds}
 */
public record AcidIslandConfiguration(
        boolean enabled,
        Sea sea,
        AcidRules rules,
        List<String> waterProtection,
        List<String> waterEffects,
        Purification purification) {

    private static final Logger LOGGER = Logger.getLogger(AcidIslandConfiguration.class.getName());

    /**
     * The sea an island is made in.
     *
     * @param belowIsland how many blocks below the island's start height the surface stands
     * @param depth how many blocks of water, above the floor
     * @param radius how far from the island's centre the sea reaches; keep it inside half the grid spacing
     * @param floor the block the sea stands on
     */
    public record Sea(int belowIsland, int depth, int radius, String floor) {

        public static final Sea SHIPPED = new Sea(2, 4, 64, "SAND");

        public Sea {
            Objects.requireNonNull(floor, "floor must not be null");
            if (depth < 1 || radius < 1 || belowIsland < 0) {
                throw new IllegalArgumentException("the sea needs a depth and a radius of at least 1");
            }
        }
    }

    /** How acid water is made clean. Rain a cauldron catches is clean already. */
    public record Purification(boolean furnace, boolean brewingWithCoal) {}

    public AcidIslandConfiguration {
        Objects.requireNonNull(sea, "sea must not be null");
        Objects.requireNonNull(rules, "rules must not be null");
        Objects.requireNonNull(purification, "purification must not be null");
        waterProtection = List.copyOf(waterProtection);
        waterEffects = List.copyOf(waterEffects);
    }

    public static AcidIslandConfiguration defaultConfiguration() {
        return new AcidIslandConfiguration(
                true,
                Sea.SHIPPED,
                AcidRules.shipped(),
                List.of("water_breathing"),
                List.of("poison:0:3"),
                new Purification(true, true));
    }

    public static AcidIslandConfiguration load(ConfigurationNode root) {
        AcidIslandConfiguration shipped = defaultConfiguration();
        Sea sea;
        ConfigurationNode seaNode = root.node("sea");
        try {
            sea = new Sea(
                    seaNode.node("below-island").getInt(Sea.SHIPPED.belowIsland()),
                    seaNode.node("depth").getInt(Sea.SHIPPED.depth()),
                    seaNode.node("radius").getInt(Sea.SHIPPED.radius()),
                    seaNode.node("floor").getString(Sea.SHIPPED.floor()).trim());
        } catch (IllegalArgumentException e) {
            LOGGER.warning(() -> "modules/acidisland.conf sea: " + e.getMessage() + ". The shipped sea is used.");
            sea = Sea.SHIPPED;
        }
        ConfigurationNode hazard = root.node("hazard");
        AcidRules rules;
        try {
            rules = new AcidRules(
                    hazard.node("water-damage").getDouble(shipped.rules().waterDamage()),
                    hazard.node("rain-damage").getDouble(shipped.rules().rainDamage()),
                    hazard.node("helmet-blocks-rain").getBoolean(shipped.rules().helmetBlocksRain()),
                    durationOf(
                            hazard.node("check-every").getString(""),
                            shipped.rules().checkEvery()));
        } catch (IllegalArgumentException e) {
            LOGGER.warning(
                    () -> "modules/acidisland.conf hazard: " + e.getMessage() + ". The shipped numbers are used.");
            rules = shipped.rules();
        }
        ConfigurationNode purification = root.node("purification");
        return new AcidIslandConfiguration(
                root.node("enabled").getBoolean(true),
                sea,
                rules,
                strings(hazard.node("water-protection"), shipped.waterProtection()),
                strings(hazard.node("water-effects"), shipped.waterEffects()),
                new Purification(
                        purification.node("furnace").getBoolean(true),
                        purification.node("brewing-with-coal").getBoolean(true)));
    }

    private static List<String> strings(ConfigurationNode node, List<String> fallback) {
        if (node.virtual()) {
            return fallback;
        }
        List<String> read = new ArrayList<>();
        for (ConfigurationNode child : node.childrenList()) {
            String value = child.getString("").trim();
            if (!value.isEmpty()) {
                read.add(value);
            }
        }
        return read;
    }

    /** A window written as {@code 500ms}, {@code 2s} or a plain number of seconds. */
    private static Duration durationOf(String written, Duration fallback) {
        String raw = written.trim().toLowerCase(java.util.Locale.ROOT);
        if (raw.isEmpty()) {
            return fallback;
        }
        try {
            if (raw.endsWith("ms")) {
                return Duration.ofMillis(
                        Long.parseLong(raw.substring(0, raw.length() - 2).trim()));
            }
            if (raw.endsWith("s")) {
                return Duration.ofMillis(Math.round(
                        Double.parseDouble(raw.substring(0, raw.length() - 1).trim()) * 1000));
            }
            return Duration.ofMillis(Math.round(Double.parseDouble(raw) * 1000));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
