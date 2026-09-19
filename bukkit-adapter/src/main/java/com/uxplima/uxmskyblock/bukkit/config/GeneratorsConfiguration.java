package com.uxplima.uxmskyblock.bukkit.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.bukkit.Material;

import org.spongepowered.configurate.ConfigurationNode;

/**
 * Immutable configuration holder for cobblestone and stone generator ore generation rates by tier.
 */
public record GeneratorsConfiguration(boolean enabled, Map<Integer, Map<Material, Double>> tierRates) {

    public static final boolean DEFAULT_ENABLED = true;

    public GeneratorsConfiguration {
        Map<Integer, Map<Material, Double>> copy = new LinkedHashMap<>();
        if (tierRates != null) {
            for (Map.Entry<Integer, Map<Material, Double>> entry : tierRates.entrySet()) {
                copy.put(entry.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(entry.getValue())));
            }
        }
        tierRates = Collections.unmodifiableMap(copy);
    }

    /**
     * Determines which material should generate for the given tier based on the normalized random value [0.0, 1.0).
     *
     * @param tier generator tier level (0-based or 1-based)
     * @param randomValue random roll value in [0.0, 1.0)
     * @return selected Material
     */
    public Material roll(int tier, double randomValue) {
        Map<Material, Double> rates = tierRates.get(tier);
        if (rates == null || rates.isEmpty()) {
            rates = tierRates.get(0);
        }
        if (rates == null || rates.isEmpty()) {
            return Material.COBBLESTONE;
        }

        double totalWeight = 0.0;
        for (double w : rates.values()) {
            if (w > 0.0) {
                totalWeight += w;
            }
        }
        if (totalWeight <= 0.0) {
            return Material.COBBLESTONE;
        }

        double target = Math.max(0.0, Math.min(0.999999, randomValue)) * totalWeight;
        double cumulative = 0.0;
        for (Map.Entry<Material, Double> entry : rates.entrySet()) {
            if (entry.getValue() <= 0.0) {
                continue;
            }
            cumulative += entry.getValue();
            if (target < cumulative) {
                return entry.getKey();
            }
        }

        return Material.COBBLESTONE;
    }

    public static GeneratorsConfiguration defaultConfiguration() {
        Map<Integer, Map<Material, Double>> rates = new LinkedHashMap<>();

        // Tier 0 (Base / un-upgraded)
        rates.put(0, Map.of(Material.COBBLESTONE, 1.0));

        // Tier 1
        Map<Material, Double> t1 = new LinkedHashMap<>();
        t1.put(Material.COBBLESTONE, 0.70);
        t1.put(Material.COAL_ORE, 0.20);
        t1.put(Material.IRON_ORE, 0.10);
        rates.put(1, t1);

        // Tier 2
        Map<Material, Double> t2 = new LinkedHashMap<>();
        t2.put(Material.COBBLESTONE, 0.55);
        t2.put(Material.COAL_ORE, 0.20);
        t2.put(Material.IRON_ORE, 0.15);
        t2.put(Material.GOLD_ORE, 0.06);
        t2.put(Material.LAPIS_ORE, 0.04);
        rates.put(2, t2);

        // Tier 3
        Map<Material, Double> t3 = new LinkedHashMap<>();
        t3.put(Material.COBBLESTONE, 0.40);
        t3.put(Material.IRON_ORE, 0.20);
        t3.put(Material.GOLD_ORE, 0.15);
        t3.put(Material.REDSTONE_ORE, 0.10);
        t3.put(Material.LAPIS_ORE, 0.10);
        t3.put(Material.DIAMOND_ORE, 0.05);
        rates.put(3, t3);

        // Tier 4
        Map<Material, Double> t4 = new LinkedHashMap<>();
        t4.put(Material.COBBLESTONE, 0.30);
        t4.put(Material.IRON_ORE, 0.18);
        t4.put(Material.GOLD_ORE, 0.15);
        t4.put(Material.REDSTONE_ORE, 0.12);
        t4.put(Material.DIAMOND_ORE, 0.10);
        t4.put(Material.EMERALD_ORE, 0.10);
        t4.put(Material.ANCIENT_DEBRIS, 0.05);
        rates.put(4, t4);

        return new GeneratorsConfiguration(DEFAULT_ENABLED, rates);
    }

    public static GeneratorsConfiguration load(ConfigurationNode root) {
        return fromNode(root);
    }

    public static GeneratorsConfiguration fromNode(ConfigurationNode root) {
        Objects.requireNonNull(root, "root configuration node must not be null");

        boolean enabled = root.node("enabled").getBoolean(DEFAULT_ENABLED);
        ConfigurationNode tiersNode = root.node("tiers");
        if (tiersNode.virtual() || !tiersNode.isMap()) {
            return defaultConfiguration();
        }

        Map<Integer, Map<Material, Double>> rates = new LinkedHashMap<>();
        for (Map.Entry<Object, ? extends ConfigurationNode> entry :
                tiersNode.childrenMap().entrySet()) {
            try {
                int tierNum = Integer.parseInt(String.valueOf(entry.getKey()));
                ConfigurationNode tierNode = entry.getValue();
                Map<Material, Double> matRates = new LinkedHashMap<>();

                ConfigurationNode ratesNode = tierNode.node("rates");
                if (!ratesNode.virtual() && ratesNode.isMap()) {
                    for (Map.Entry<Object, ? extends ConfigurationNode> rEntry :
                            ratesNode.childrenMap().entrySet()) {
                        String matName = String.valueOf(rEntry.getKey()).toUpperCase(Locale.ROOT);
                        Material mat = Material.matchMaterial(matName);
                        if (mat != null) {
                            matRates.put(mat, rEntry.getValue().getDouble(0.0));
                        }
                    }
                }
                rates.put(tierNum, matRates);
            } catch (NumberFormatException ignored) {
                // Skip invalid tier keys
            }
        }

        if (rates.isEmpty()) {
            return defaultConfiguration();
        }

        return new GeneratorsConfiguration(enabled, rates);
    }
}
