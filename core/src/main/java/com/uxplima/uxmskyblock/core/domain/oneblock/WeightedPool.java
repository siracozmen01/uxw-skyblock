package com.uxplima.uxmskyblock.core.domain.oneblock;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * Keys drawn by weight: a key of weight 3 comes up three times as often as one of weight 1.
 *
 * <p>The keys are whatever the platform names things by, a block or a creature, and mean nothing here.
 */
public record WeightedPool(Map<String, Double> weights) {

    public WeightedPool {
        Objects.requireNonNull(weights, "weights");
        Map<String, Double> kept = new LinkedHashMap<>();
        weights.forEach((key, weight) -> {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(weight, "weight");
            if (!(weight >= 0) || weight.isInfinite()) {
                throw new IllegalArgumentException("The weight of " + key + " must be zero or more: " + weight);
            }
            if (weight > 0) {
                kept.put(key, weight);
            }
        });
        weights = java.util.Collections.unmodifiableMap(kept);
    }

    /** A pool with nothing in it, which draws nothing. */
    public static WeightedPool empty() {
        return new WeightedPool(Map.of());
    }

    public boolean isEmpty() {
        return weights.isEmpty();
    }

    /** One key, drawn by weight, or nothing when the pool is empty. */
    public java.util.Optional<String> draw(RandomGenerator random) {
        Objects.requireNonNull(random, "random");
        double total = 0;
        for (double weight : weights.values()) {
            total += weight;
        }
        if (total <= 0) {
            return java.util.Optional.empty();
        }
        double roll = random.nextDouble() * total;
        java.util.List<Map.Entry<String, Double>> entries = java.util.List.copyOf(weights.entrySet());
        for (Map.Entry<String, Double> entry : entries) {
            roll -= entry.getValue();
            if (roll < 0) {
                return java.util.Optional.of(entry.getKey());
            }
        }
        // Rounding can leave a sliver past the last weight; it belongs to the last key.
        return java.util.Optional.of(entries.getLast().getKey());
    }
}
