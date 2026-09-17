package com.uxplima.uxmskyblock.core.domain.level;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IslandMaterialIndexTest {

    private IslandId islandId;
    private MaterialValuationIndex valuationIndex;
    private IslandMaterialIndex materialIndex;

    @BeforeEach
    void setUp() {
        islandId = IslandId.of(UUID.randomUUID());
        valuationIndex = new MaterialValuationIndex();

        // 1 diamond block = 100 level points, 500 minor units ($5.00)
        valuationIndex.setWeight("minecraft:diamond_block", 100L);
        valuationIndex.setPrice("minecraft:diamond_block", 500L);

        // 1 emerald block = 200 level points, 1000 minor units ($10.00)
        valuationIndex.setWeight("minecraft:emerald_block", 200L);
        valuationIndex.setPrice("minecraft:emerald_block", 1000L);

        materialIndex = new IslandMaterialIndex(islandId, valuationIndex);
    }

    @Test
    @DisplayName("increment and decrement update block counts and accumulators in amortized O(1)")
    void incrementAndDecrement() {
        materialIndex.increment("minecraft:diamond_block", 10);
        assertThat(materialIndex.getCount("minecraft:diamond_block")).isEqualTo(10);
        assertThat(materialIndex.getCachedLevelScore()).isEqualTo(1000L);
        assertThat(materialIndex.getCachedEconomicWorth()).isEqualTo(5000L);

        materialIndex.increment("minecraft:emerald_block", 5);
        assertThat(materialIndex.getCount("minecraft:emerald_block")).isEqualTo(5);
        assertThat(materialIndex.getCachedLevelScore()).isEqualTo(2000L); // 1000 + 1000
        assertThat(materialIndex.getCachedEconomicWorth()).isEqualTo(10000L); // 5000 + 5000

        // Decrement 3 diamond blocks
        materialIndex.decrement("minecraft:diamond_block", 3);
        assertThat(materialIndex.getCount("minecraft:diamond_block")).isEqualTo(7);
        assertThat(materialIndex.getCachedLevelScore()).isEqualTo(1700L);
        assertThat(materialIndex.getCachedEconomicWorth()).isEqualTo(8500L);

        // Decrement beyond zero is bounded at zero
        materialIndex.decrement("minecraft:diamond_block", 100);
        assertThat(materialIndex.getCount("minecraft:diamond_block")).isEqualTo(0);
        assertThat(materialIndex.getCachedLevelScore()).isEqualTo(1000L); // emeralds only
        assertThat(materialIndex.getCachedEconomicWorth()).isEqualTo(5000L);
    }

    @Test
    @DisplayName("updateMaterialPrice dynamically recalculates net worth accumulator via delta in O(1)")
    void dynamicPriceUpdate() {
        materialIndex.increment("minecraft:diamond_block", 20); // 20 * 500 = 10,000 worth
        assertThat(materialIndex.getCachedEconomicWorth()).isEqualTo(10000L);

        // Price increases from 500 to 750 (delta +250 per block * 20 = +5000)
        materialIndex.updateMaterialPrice("minecraft:diamond_block", 750L);
        assertThat(materialIndex.getCachedEconomicWorth()).isEqualTo(15000L);
        assertThat(valuationIndex.priceOf("minecraft:diamond_block")).isEqualTo(750L);

        // Price drops to 200 (delta -550 per block * 20 = -11000 -> 4000)
        materialIndex.updateMaterialPrice("minecraft:diamond_block", 200L);
        assertThat(materialIndex.getCachedEconomicWorth()).isEqualTo(4000L);
    }

    @Test
    @DisplayName("recomputeAll recalculates full histogram in O(M)")
    void recomputeAll() {
        materialIndex.increment("minecraft:diamond_block", 5);
        materialIndex.increment("minecraft:emerald_block", 10);

        // Change weights externally
        valuationIndex.setWeight("minecraft:diamond_block", 150L);
        valuationIndex.setWeight("minecraft:emerald_block", 300L);

        materialIndex.recomputeAll();
        // 5 * 150 + 10 * 300 = 750 + 3000 = 3750
        assertThat(materialIndex.getCachedLevelScore()).isEqualTo(3750L);
    }
}
