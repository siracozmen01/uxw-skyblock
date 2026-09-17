package com.uxplima.uxmskyblock.core.domain.level;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Immutable or thread-safe lookup registry mapping material keys to level point weights
 * and economic prices (in minor units).
 */
public final class MaterialValuationIndex {

    private final Map<String, Long> weights = new ConcurrentHashMap<>();
    private final Map<String, Long> pricesMinorUnits = new ConcurrentHashMap<>();

    public MaterialValuationIndex() {}

    public MaterialValuationIndex(Map<String, Long> initialWeights, Map<String, Long> initialPrices) {
        if (initialWeights != null) {
            this.weights.putAll(initialWeights);
        }
        if (initialPrices != null) {
            this.pricesMinorUnits.putAll(initialPrices);
        }
    }

    public long weightOf(String materialKey) {
        Objects.requireNonNull(materialKey, "materialKey");
        return weights.getOrDefault(materialKey, 0L);
    }

    public long priceOf(String materialKey) {
        Objects.requireNonNull(materialKey, "materialKey");
        return pricesMinorUnits.getOrDefault(materialKey, 0L);
    }

    public void setWeight(String materialKey, long weight) {
        Objects.requireNonNull(materialKey, "materialKey");
        weights.put(materialKey, weight);
    }

    public void setPrice(String materialKey, long priceMinorUnits) {
        Objects.requireNonNull(materialKey, "materialKey");
        pricesMinorUnits.put(materialKey, priceMinorUnits);
    }

    public Map<String, Long> allWeights() {
        return Map.copyOf(weights);
    }

    public Map<String, Long> allPrices() {
        return Map.copyOf(pricesMinorUnits);
    }
}
