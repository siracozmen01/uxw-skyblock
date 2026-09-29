package com.uxplima.uxmskyblock.bukkit.config;

import java.util.Objects;
import java.util.logging.Logger;

import org.spongepowered.configurate.ConfigurationNode;

/**
 * {@code modules/poseidon.conf}: whether islands can be Poseidon islands, and the ocean they are made
 * at the bottom of.
 */
public record PoseidonConfiguration(boolean enabled, Ocean ocean) {

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

    public PoseidonConfiguration {
        Objects.requireNonNull(ocean, "ocean must not be null");
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
        return new PoseidonConfiguration(root.node("enabled").getBoolean(true), ocean);
    }
}
