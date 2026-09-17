package com.uxplima.uxmskyblock.core.domain.island;

import static org.assertj.core.api.Assertions.assertThat;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class IslandBoundsPropertyTest {

    @Provide
    Arbitrary<IslandBounds> validBounds() {
        Arbitrary<Integer> centers = Arbitraries.integers().between(-100000, 100000);
        Arbitrary<Integer> radii = Arbitraries.integers().between(1, 1000);
        return Combinators.combine(centers, centers, radii).as(IslandBounds::fromCenterAndRadius);
    }

    @Property
    void centerIsAlwaysContained(@ForAll("validBounds") IslandBounds bounds) {
        assertThat(bounds.contains(bounds.centerX(), bounds.centerZ())).isTrue();
    }

    @Property
    void boundaryPointsAreContained(@ForAll("validBounds") IslandBounds bounds) {
        assertThat(bounds.contains(bounds.minX(), bounds.minZ())).isTrue();
        assertThat(bounds.contains(bounds.maxX(), bounds.maxZ())).isTrue();
        assertThat(bounds.contains(bounds.minX(), bounds.maxZ())).isTrue();
        assertThat(bounds.contains(bounds.maxX(), bounds.minZ())).isTrue();
    }

    @Property
    void pointsOutsideBoundaryAreNotContained(@ForAll("validBounds") IslandBounds bounds) {
        assertThat(bounds.contains(bounds.minX() - 1, bounds.centerZ())).isFalse();
        assertThat(bounds.contains(bounds.maxX() + 1, bounds.centerZ())).isFalse();
        assertThat(bounds.contains(bounds.centerX(), bounds.minZ() - 1)).isFalse();
        assertThat(bounds.contains(bounds.centerX(), bounds.maxZ() + 1)).isFalse();
    }

    @Property
    void expansionIsMonotonicallyContaining(
            @ForAll("validBounds") IslandBounds bounds, @ForAll("validRadii") int delta) {
        IslandBounds expanded = bounds.expand(delta);
        assertThat(expanded.contains(bounds.minX(), bounds.minZ())).isTrue();
        assertThat(expanded.contains(bounds.maxX(), bounds.maxZ())).isTrue();
        assertThat(expanded.radius()).isEqualTo(bounds.radius() + delta);
    }

    @Provide
    Arbitrary<Integer> validRadii() {
        return Arbitraries.integers().between(1, 500);
    }
}
