package com.uxplima.uxmskyblock.bukkit.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Logger;

import org.bukkit.Material;

import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;

/**
 * The Brix game mode, as {@code modules/brix.conf} writes it: islands that are creative building plots,
 * with the ground they are laid on.
 *
 * @param enabled whether islands can be made as Brix plots
 * @param ground the ground a new plot is laid on
 */
public record BrixConfiguration(boolean enabled, Ground ground) {

    private static final Logger LOGGER = Logger.getLogger(BrixConfiguration.class.getName());

    public BrixConfiguration {
        Objects.requireNonNull(ground, "ground must not be null");
    }

    /**
     * The flat ground under a new plot.
     *
     * @param radius how far the ground reaches from the centre, each way
     * @param layers the ground's layers, top first; none lays no ground and the plot is void
     */
    public record Ground(int radius, List<Material> layers) {

        /** The widest ground a plot is laid on. */
        public static final int MAX_RADIUS = 128;

        /** The most layers a ground has. */
        public static final int MAX_LAYERS = 64;

        public static final Ground SHIPPED = new Ground(
                32, List.of(Material.GRASS_BLOCK, Material.DIRT, Material.DIRT, Material.DIRT, Material.BEDROCK));

        public Ground {
            layers = List.copyOf(layers);
            if (radius < 0 || radius > MAX_RADIUS) {
                throw new IllegalArgumentException("the radius must be from 0 to " + MAX_RADIUS);
            }
            if (layers.size() > MAX_LAYERS) {
                throw new IllegalArgumentException("a ground has at most " + MAX_LAYERS + " layers");
            }
            for (Material layer : layers) {
                if (!layer.isBlock()) {
                    throw new IllegalArgumentException(layer + " is no block");
                }
            }
        }
    }

    public static BrixConfiguration defaultConfiguration() {
        return new BrixConfiguration(true, Ground.SHIPPED);
    }

    public static BrixConfiguration load(ConfigurationNode root) {
        ConfigurationNode written = root.node("ground");
        Ground ground;
        try {
            ground = new Ground(
                    written.node("radius").getInt(Ground.SHIPPED.radius()),
                    written.node("layers").virtual() ? Ground.SHIPPED.layers() : layers(written.node("layers")));
        } catch (IllegalArgumentException | SerializationException e) {
            LOGGER.warning(() -> "modules/brix.conf ground: " + e.getMessage() + ". The shipped ground is used.");
            ground = Ground.SHIPPED;
        }
        return new BrixConfiguration(root.node("enabled").getBoolean(true), ground);
    }

    private static List<Material> layers(ConfigurationNode node) throws SerializationException {
        List<Material> layers = new ArrayList<>();
        for (String written : node.getList(String.class, List.of())) {
            Material material = Material.matchMaterial(written.trim().toUpperCase(Locale.ROOT));
            if (material == null || !material.isBlock()) {
                throw new IllegalArgumentException(written + " is no block");
            }
            layers.add(material);
        }
        return layers;
    }
}
