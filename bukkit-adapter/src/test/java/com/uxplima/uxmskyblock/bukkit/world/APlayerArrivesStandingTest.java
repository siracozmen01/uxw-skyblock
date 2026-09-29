package com.uxplima.uxmskyblock.bukkit.world;

import static org.assertj.core.api.Assertions.assertThat;

import org.bukkit.Material;
import org.bukkit.World;

import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A player arrives on a new island standing: on ground that does not hurt, with room for feet and
 * head, at the height nearest the one planned. On generated land the planned height was inside a hill
 * or over a drop.
 */
class APlayerArrivesStandingTest extends MockBukkitHarness {

    private static final int X = 40;
    private static final int Z = -40;

    @SuppressWarnings("NullAway.Init")
    private World world;

    @BeforeEach
    void setUpWorld() {
        world = server.addSimpleWorld("boxed");
        fill(world.getMinHeight(), world.getMaxHeight() - 1, Material.AIR);
    }

    @Test
    @DisplayName("A planned height already safe is kept")
    void aSafeHeightIsKept() {
        fill(60, 99, Material.STONE);

        assertThat(SafeArrival.standingY(world, X, 100, Z)).hasValue(100);
    }

    @Test
    @DisplayName("Over a drop the player arrives on the ground below, and inside a hill on its top")
    void overADropAndInsideAHill() {
        fill(60, 80, Material.STONE);
        assertThat(SafeArrival.standingY(world, X, 100, Z)).hasValue(81);

        fill(60, 120, Material.STONE);
        assertThat(SafeArrival.standingY(world, X, 100, Z)).hasValue(121);
    }

    @Test
    @DisplayName("In a cave the nearest floor is chosen over the roof far above")
    void theNearestFloorWins() {
        fill(60, world.getMaxHeight() - 1, Material.STONE);
        fill(95, 97, Material.AIR);

        assertThat(SafeArrival.standingY(world, X, 100, Z)).hasValue(95);
    }

    @Test
    @DisplayName("Lava or magma underfoot and water at the head are not safe, and empty air has no answer")
    void hazardsAreRefused() {
        fill(60, 79, Material.STONE);
        world.getBlockAt(X, 80, Z).setType(Material.LAVA);
        assertThat(SafeArrival.safe(world, X, 81, Z)).isFalse();
        world.getBlockAt(X, 80, Z).setType(Material.MAGMA_BLOCK);
        assertThat(SafeArrival.safe(world, X, 81, Z))
                .describedAs("solid, and it burns")
                .isFalse();

        world.getBlockAt(X, 80, Z).setType(Material.STONE);
        world.getBlockAt(X, 82, Z).setType(Material.WATER);
        assertThat(SafeArrival.safe(world, X, 81, Z)).isFalse();

        fill(world.getMinHeight(), world.getMaxHeight() - 1, Material.AIR);
        assertThat(SafeArrival.standingY(world, X, 100, Z)).isEmpty();
    }

    @Test
    @DisplayName("Nobody arrives above the world's logical height, the Nether's bedrock roof")
    void notAboveTheRoof() {
        World nether = org.mockito.Mockito.spy(world);
        org.mockito.Mockito.when(nether.getLogicalHeight()).thenReturn(80);
        int roof = nether.getMinHeight() + 80;
        fill(60, roof - 1, Material.STONE);

        assertThat(SafeArrival.safe(nether, X, roof, Z)).isFalse();
        assertThat(SafeArrival.standingY(nether, X, 70, Z)).isEmpty();
        assertThat(SafeArrival.standingY(world, X, 70, Z))
                .describedAs("where play goes on above it, the top of the rock is fine")
                .hasValue(roof);
    }

    @Test
    @DisplayName("Where rock reaches past the search, room is cleared for feet and head and the ground is kept")
    void roomIsMadeInSolidRock() {
        fill(world.getMinHeight(), world.getMaxHeight() - 1, Material.NETHERRACK);
        world.getBlockAt(X, 100, Z).setType(Material.GRASS_BLOCK);
        assertThat(SafeArrival.standingY(world, X, 101, Z)).isEmpty();

        SafeArrival.makeRoom(world, X, 101, Z);

        assertThat(SafeArrival.safe(world, X, 101, Z)).isTrue();
        assertThat(world.getBlockAt(X, 100, Z).getType()).isEqualTo(Material.GRASS_BLOCK);
        assertThat(world.getBlockAt(X, 103, Z).getType()).isEqualTo(Material.NETHERRACK);
    }

    private void fill(int fromY, int toY, Material material) {
        for (int y = fromY; y <= toY; y++) {
            world.getBlockAt(X, y, Z).setType(material);
        }
    }
}
