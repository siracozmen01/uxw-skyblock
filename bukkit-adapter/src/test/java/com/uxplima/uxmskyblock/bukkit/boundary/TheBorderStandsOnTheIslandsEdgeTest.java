package com.uxplima.uxmskyblock.bukkit.boundary;

import static org.assertj.core.api.Assertions.assertThat;

import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The border a player sees stands exactly on the edge of the island they own.
 *
 * <p>An island of radius 50 holds 101 blocks across, from the centre block 50 either way. The border
 * was 100 wide around the middle of the centre block, so it ran through the middle of the edge blocks:
 * half of each edge block was outside the border and still the island's.
 */
class TheBorderStandsOnTheIslandsEdgeTest {

    @ParameterizedTest(name = "radius {0}")
    @ValueSource(ints = {1, 25, 50, 75, 128})
    void theBorderEdgesAreTheIslandEdges(int radius) {
        IslandBounds bounds = IslandBounds.fromCenterAndRadius(5120, -5120, radius);
        double centreX = bounds.centerX() + 0.5;
        double centreZ = bounds.centerZ() + 0.5;
        double half = WorldBorderPacketAdapter.widthOf(radius) / 2.0;

        assertThat(centreX - half).describedAs("west edge").isEqualTo((double) bounds.minX());
        assertThat(centreX + half).describedAs("east edge").isEqualTo(bounds.maxX() + 1.0);
        assertThat(centreZ - half).describedAs("north edge").isEqualTo((double) bounds.minZ());
        assertThat(centreZ + half).describedAs("south edge").isEqualTo(bounds.maxZ() + 1.0);
    }
}
