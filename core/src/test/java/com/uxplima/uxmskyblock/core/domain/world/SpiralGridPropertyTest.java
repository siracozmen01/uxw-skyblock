package com.uxplima.uxmskyblock.core.domain.world;

import static org.assertj.core.api.Assertions.assertThat;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SpiralGridPropertyTest {

    private final SpiralGridCoordinateAllocator allocator = new SpiralGridCoordinateAllocator(5120);

    @Test
    @DisplayName("index 0 maps to (0, 0)")
    void indexZeroMapsToOrigin() {
        IslandCoordinates origin = allocator.coordinatesForIndex(0);
        assertThat(origin.x()).isZero();
        assertThat(origin.z()).isZero();
        assertThat(allocator.indexForCoordinates(0, 0)).isZero();
    }

    @Property
    void reversibility(@ForAll("validIndices") long index) {
        IslandCoordinates coords = allocator.coordinatesForIndex(index);
        long resolved = allocator.indexForCoordinates(coords.x(), coords.z());
        assertThat(resolved).isEqualTo(index);
    }

    @Property
    void distinctIndicesProduceDistinctCoordinates(@ForAll("validIndices") long i1, @ForAll("validIndices") long i2) {
        if (i1 != i2) {
            IslandCoordinates c1 = allocator.coordinatesForIndex(i1);
            IslandCoordinates c2 = allocator.coordinatesForIndex(i2);
            assertThat(c1).isNotEqualTo(c2);
        }
    }

    @Property
    void gridPointsAreExactMultiplesOfSpacing(@ForAll("validIndices") long index) {
        IslandCoordinates coords = allocator.coordinatesForIndex(index);
        assertThat(coords.x() % allocator.gridSpacing()).isZero();
        assertThat(coords.z() % allocator.gridSpacing()).isZero();
        assertThat(allocator.isGridPoint(coords.x(), coords.z())).isTrue();
    }

    @Property
    void offGridPointsAreRejected(@ForAll("validIndices") long index, @ForAll("offGridOffsets") int offset) {
        IslandCoordinates coords = allocator.coordinatesForIndex(index);
        int offX = coords.x() + offset;
        int offZ = coords.z() + offset;
        assertThat(allocator.indexForCoordinates(offX, offZ)).isEqualTo(-1);
        assertThat(allocator.isGridPoint(offX, offZ)).isFalse();
    }

    @Provide
    Arbitrary<Long> validIndices() {
        return Arbitraries.longs().between(0, 100_000);
    }

    @Provide
    Arbitrary<Integer> offGridOffsets() {
        return Arbitraries.integers().between(1, 5119);
    }
}
