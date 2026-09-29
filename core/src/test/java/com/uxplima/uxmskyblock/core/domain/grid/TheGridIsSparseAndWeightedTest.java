package com.uxplima.uxmskyblock.core.domain.grid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A SkyGrid island's blocks stand one every few blocks, lined up on the arrival, in their weights. */
class TheGridIsSparseAndWeightedTest {

    @Test
    @DisplayName("A grid block stands every spacing blocks along each axis, on the arrival, and nowhere past the edge")
    void theGridIsSparse() {
        GridLayout layout = new GridLayout(4, 12, 8, 4);

        assertThat(layout.isNode(0, 0, 0)).isTrue();
        assertThat(layout.isNode(4, -8, -12)).isTrue();
        assertThat(layout.isNode(2, 0, 0)).isFalse();
        assertThat(layout.isNode(0, 1, 0)).isFalse();
        assertThat(layout.isNode(16, 0, 0)).describedAs("past the radius").isFalse();
        assertThat(layout.isNode(0, -12, 0)).describedAs("below the grid").isFalse();
        assertThat(layout.isNode(0, 8, 0)).describedAs("above the grid").isFalse();
        assertThat(layout.alignUp(-13)).isEqualTo(-12);
        assertThat(layout.alignUp(5)).isEqualTo(8);
        assertThat(layout.alignUp(8)).isEqualTo(8);
        assertThatThrownBy(() -> new GridLayout(1, 12, 8, 4)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Blocks stand in the grid as often as their weights say")
    void theWeightsHold() {
        GridPalette palette =
                new GridPalette(List.of(GridPalette.Entry.parse("dirt:30"), GridPalette.Entry.parse("STONE:10")));
        SplittableRandom random = new SplittableRandom(5);
        Map<String, Integer> counted = new HashMap<>();
        for (int i = 0; i < 40_000; i++) {
            counted.merge(palette.pick(random.nextDouble()), 1, Integer::sum);
        }

        assertThat(palette.totalWeight()).isEqualTo(40);
        assertThat(counted.getOrDefault("DIRT", 0) / (double) counted.getOrDefault("STONE", 1))
                .isBetween(2.8, 3.2);
        assertThat(palette.pick(0.0)).isEqualTo("DIRT");
        assertThat(palette.pick(0.999)).isEqualTo("STONE");
        assertThatThrownBy(() -> GridPalette.Entry.parse("DIRT:0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GridPalette(List.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
