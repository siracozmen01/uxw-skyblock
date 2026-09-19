package com.uxplima.uxmskyblock.core.domain.level;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * Real-Time Material Histogram Index tracking placed block distributions, live level points,
 * and economic net worth for an individual island.
 *
 * <p>Computational Complexities:
 * <ul>
 *   <li>Block event mutation (place / break): Amortized {@code O(1)} via atomic count &amp; accumulator update.</li>
 *   <li>Single material price update: Amortized {@code O(1)} via delta adjustment.</li>
 *   <li>Full economic recomputation: {@code O(M)}, where {@code M} is distinct tracked material types.</li>
 * </ul>
 */
public final class IslandMaterialIndex {

    private final IslandId islandId;
    private final MaterialValuationIndex valuationIndex;
    private final ConcurrentHashMap<String, AtomicInteger> materialCounts = new ConcurrentHashMap<>();

    private final AtomicLong cachedLevelScore = new AtomicLong(0L);
    private final AtomicLong cachedEconomicWorth = new AtomicLong(0L);

    public IslandMaterialIndex(IslandId islandId, MaterialValuationIndex valuationIndex) {
        this.islandId = Objects.requireNonNull(islandId, "islandId");
        this.valuationIndex = Objects.requireNonNull(valuationIndex, "valuationIndex");
    }

    public IslandId islandId() {
        return islandId;
    }

    public MaterialValuationIndex valuationIndex() {
        return valuationIndex;
    }

    /**
     * Increments the placed count of the given material in amortized O(1) time.
     *
     * @param materialKey namespaced material identifier
     * @param amount number of blocks placed
     * @return updated total count of the material
     */
    public int increment(String materialKey, int amount) {
        Objects.requireNonNull(materialKey, "materialKey");
        if (amount <= 0) {
            return getCount(materialKey);
        }

        AtomicInteger counter = materialCounts.computeIfAbsent(materialKey, k -> new AtomicInteger(0));
        int newCount = counter.addAndGet(amount);

        long weight = valuationIndex.weightOf(materialKey);
        long price = valuationIndex.priceOf(materialKey);

        if (weight != 0) {
            cachedLevelScore.addAndGet((long) amount * weight);
        }
        if (price != 0) {
            cachedEconomicWorth.addAndGet((long) amount * price);
        }

        return newCount;
    }

    /**
     * Decrements the placed count of the given material in amortized O(1) time.
     *
     * @param materialKey namespaced material identifier
     * @param amount number of blocks broken
     * @return updated total count of the material (bounded at zero)
     */
    public int decrement(String materialKey, int amount) {
        Objects.requireNonNull(materialKey, "materialKey");
        if (amount <= 0) {
            return getCount(materialKey);
        }

        AtomicInteger counter = materialCounts.get(materialKey);
        if (counter == null) {
            return 0;
        }

        int actualRemoved;
        int newCount;
        while (true) {
            int current = counter.get();
            if (current <= 0) {
                return 0;
            }
            actualRemoved = Math.min(current, amount);
            newCount = current - actualRemoved;
            if (counter.compareAndSet(current, newCount)) {
                break;
            }
        }

        long weight = valuationIndex.weightOf(materialKey);
        long price = valuationIndex.priceOf(materialKey);

        if (weight != 0) {
            cachedLevelScore.addAndGet(-((long) actualRemoved * weight));
        }
        if (price != 0) {
            cachedEconomicWorth.addAndGet(-((long) actualRemoved * price));
        }

        return newCount;
    }

    /**
     * Returns the current block count for the given material in O(1) time.
     *
     * @param materialKey namespaced material identifier
     * @return current tracked count
     */
    public int getCount(String materialKey) {
        AtomicInteger counter = materialCounts.get(materialKey);
        return (counter != null) ? counter.get() : 0;
    }

    /**
     * Returns an unmodifiable snapshot copy of all tracked material counts.
     *
     * @return map of material keys to counts
     */
    public Map<String, Integer> getCounts() {
        Map<String, Integer> snapshot = new ConcurrentHashMap<>();
        materialCounts.forEach((k, v) -> {
            int c = v.get();
            if (c > 0) {
                snapshot.put(k, c);
            }
        });
        return Collections.unmodifiableMap(snapshot);
    }

    /**
     * Returns the cached level score in O(1) time.
     *
     * @return current accumulated level score
     */
    public long getCachedLevelScore() {
        return Math.max(0L, cachedLevelScore.get());
    }

    /**
     * Returns the cached economic net worth in O(1) time.
     *
     * @return current accumulated net worth (minor units)
     */
    public long getCachedEconomicWorth() {
        return Math.max(0L, cachedEconomicWorth.get());
    }

    /**
     * Updates the price of a single material and updates the cached net worth accumulator
     * in amortized O(1) time via delta calculation without iterating other materials.
     *
     * @param materialKey namespaced material identifier
     * @param newPriceMinorUnits new unit price in minor units
     */
    public void updateMaterialPrice(String materialKey, long newPriceMinorUnits) {
        Objects.requireNonNull(materialKey, "materialKey");
        long oldPrice = valuationIndex.priceOf(materialKey);
        valuationIndex.setPrice(materialKey, newPriceMinorUnits);

        int count = getCount(materialKey);
        if (count > 0) {
            long priceDelta = newPriceMinorUnits - oldPrice;
            long worthDelta = (long) count * priceDelta;
            cachedEconomicWorth.addAndGet(worthDelta);
        }
    }

    /**
     * Directly adjusts the economic worth accumulator using an externally computed price delta.
     *
     * @param materialKey namespaced material identifier
     * @param priceDelta difference between new price and old price
     */
    public void applyPriceDelta(String materialKey, long priceDelta) {
        Objects.requireNonNull(materialKey, "materialKey");
        int count = getCount(materialKey);
        if (count > 0 && priceDelta != 0) {
            long worthDelta = (long) count * priceDelta;
            cachedEconomicWorth.addAndGet(worthDelta);
        }
    }

    /**
     * Fully recomputes both level score and economic net worth in O(M) time,
     * where M is the number of distinct tracked material types.
     */
    public void recomputeAll() {
        long totalScore = 0L;
        long totalWorth = 0L;

        for (Map.Entry<String, AtomicInteger> entry : materialCounts.entrySet()) {
            int count = entry.getValue().get();
            if (count > 0) {
                String mat = entry.getKey();
                long weight = valuationIndex.weightOf(mat);
                long price = valuationIndex.priceOf(mat);
                totalScore += (long) count * weight;
                totalWorth += (long) count * price;
            }
        }

        cachedLevelScore.set(totalScore);
        cachedEconomicWorth.set(totalWorth);
    }

    /**
     * Clears all tracked block counts and resets accumulators to zero.
     */
    public void reset() {
        materialCounts.clear();
        cachedLevelScore.set(0L);
        cachedEconomicWorth.set(0L);
    }
}
