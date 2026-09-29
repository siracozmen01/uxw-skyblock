package com.uxplima.uxmskyblock.bukkit.config;

import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.core.domain.hazard.PoseidonRules;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * {@code modules/poseidon.conf}: whether islands can be Poseidon islands, the ocean they are made at
 * the bottom of, and how the air hurts the players who live there.
 *
 * @param waterEffects the effects the water gives, each written {@code name:amplifier:seconds}
 */
public record PoseidonConfiguration(boolean enabled, Ocean ocean, PoseidonRules rules, List<String> waterEffects) {

    private static final Logger LOGGER = Logger.getLogger(PoseidonConfiguration.class.getName());

    /**
     * The ocean a Poseidon island lies at the bottom of.
     *
     * @param above how many blocks of water stand over the block players arrive on
     * @param depth how many blocks of water lie under it, down to the floor
     * @param radius how far from the island's centre the ocean reaches; keep it inside half the grid spacing
     * @param floor the block the ocean stands on; one that falls, such as sand, is replaced by sandstone
     */
    public record Ocean(int above, int depth, int radius, String floor) {

        public static final Ocean SHIPPED = new Ocean(24, 8, 64, "SANDSTONE");

        public Ocean {
            Objects.requireNonNull(floor, "floor must not be null");
            if (above < 2 || depth < 1 || radius < 1) {
                throw new IllegalArgumentException("the ocean needs 2 blocks above, 1 below and a radius of 1");
            }
        }
    }

    /** The effects the shipped file gives in the water: breathing, and sight a little past the beat. */
    public static final List<String> SHIPPED_WATER_EFFECTS = List.of("water_breathing:0:4", "night_vision:0:15");

    public PoseidonConfiguration {
        Objects.requireNonNull(ocean, "ocean must not be null");
        Objects.requireNonNull(rules, "rules must not be null");
        waterEffects = List.copyOf(waterEffects);
    }

    /** The shipped ocean and hazard, with Poseidon on or off. */
    public PoseidonConfiguration(boolean enabled, Ocean ocean) {
        this(enabled, ocean, PoseidonRules.shipped(), SHIPPED_WATER_EFFECTS);
    }

    public static PoseidonConfiguration defaultConfiguration() {
        return new PoseidonConfiguration(true, Ocean.SHIPPED);
    }

    public static PoseidonConfiguration load(ConfigurationNode root) {
        Ocean ocean;
        ConfigurationNode node = root.node("ocean");
        try {
            ocean = new Ocean(
                    node.node("above").getInt(Ocean.SHIPPED.above()),
                    node.node("depth").getInt(Ocean.SHIPPED.depth()),
                    node.node("radius").getInt(Ocean.SHIPPED.radius()),
                    node.node("floor").getString(Ocean.SHIPPED.floor()).trim());
        } catch (IllegalArgumentException e) {
            LOGGER.warning(() -> "modules/poseidon.conf ocean: " + e.getMessage() + ". The shipped ocean is used.");
            ocean = Ocean.SHIPPED;
        }
        PoseidonRules shipped = PoseidonRules.shipped();
        ConfigurationNode hazard = root.node("hazard");
        PoseidonRules rules;
        try {
            rules = new PoseidonRules(
                    hazard.node("dry-damage").getDouble(shipped.dryDamage()),
                    hazard.node("sun-damage").getDouble(shipped.sunDamage()),
                    hazard.node("still-damage").getDouble(shipped.stillDamage()),
                    AcidIslandConfiguration.durationOf(
                            hazard.node("still-after").getString(""), shipped.stillAfter()),
                    hazard.node("still-reach").getDouble(shipped.stillReach()),
                    hazard.node("rain-is-wet").getBoolean(shipped.rainIsWet()),
                    AcidIslandConfiguration.durationOf(
                            hazard.node("check-every").getString(""), shipped.checkEvery()));
        } catch (IllegalArgumentException e) {
            LOGGER.warning(() -> "modules/poseidon.conf hazard: " + e.getMessage() + ". The shipped numbers are used.");
            rules = shipped;
        }
        return new PoseidonConfiguration(
                root.node("enabled").getBoolean(true),
                ocean,
                rules,
                AcidIslandConfiguration.strings(hazard.node("water-effects"), SHIPPED_WATER_EFFECTS));
    }
}
