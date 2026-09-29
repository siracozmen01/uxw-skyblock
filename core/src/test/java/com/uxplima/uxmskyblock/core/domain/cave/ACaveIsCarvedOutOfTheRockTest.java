package com.uxplima.uxmskyblock.core.domain.cave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A CaveBlock island's cave is a room, tunnels and ravines carved out of rock, always the same for the
 * same island, and never through the shell around it.
 */
class ACaveIsCarvedOutOfTheRockTest {

    private static final int X = 5120;
    private static final int Y = 100;
    private static final int Z = -5120;
    private static final CavePlan.Shape SHAPE = CavePlan.Shape.SHIPPED;

    @Test
    @DisplayName("Players arrive in the room, standing on its floor, with room to stand")
    void theRoomHoldsTheArrival() {
        CavePlan plan = CavePlan.plan(42L, X, Y, Z, SHAPE);

        assertThat(plan.carved(X, Y + 1, Z)).isTrue();
        assertThat(plan.carved(X, Y + 2, Z)).isTrue();
        assertThat(plan.carved(X + SHAPE.room() - 1, Y + 2, Z)).isTrue();
    }

    @Test
    @DisplayName("The same seed plans the same cave, and another seed another one")
    void theSeedDecides() {
        assertThat(CavePlan.plan(42L, X, Y, Z, SHAPE).carves())
                .isEqualTo(CavePlan.plan(42L, X, Y, Z, SHAPE).carves());
        assertThat(CavePlan.plan(43L, X, Y, Z, SHAPE).carves())
                .isNotEqualTo(CavePlan.plan(42L, X, Y, Z, SHAPE).carves());
    }

    @Test
    @DisplayName("Tunnels and ravines wind far from the room, and never reach the shell")
    void theShellStaysWhole() {
        for (long seed = 0; seed < 40; seed++) {
            CavePlan plan = CavePlan.plan(seed, X, Y, Z, SHAPE);
            assertThat(plan.carves().size()).isGreaterThan(SHAPE.tunnels() * SHAPE.tunnelLength() / 2);
            for (CavePlan.Carve carve : plan.carves()) {
                assertThat(Math.abs(carve.x() - X - 0.5) + carve.radius()).isLessThan(SHAPE.radius());
                assertThat(Math.abs(carve.z() - Z - 0.5) + carve.radius()).isLessThan(SHAPE.radius());
                assertThat(carve.y() - carve.radius()).isGreaterThan(Y - SHAPE.below());
                assertThat(carve.y() + carve.radius()).isLessThan(Y + SHAPE.above());
            }
        }
        CavePlan plan = CavePlan.plan(7L, X, Y, Z, SHAPE);
        assertThat(plan.carves())
                .anySatisfy(carve ->
                        assertThat(Math.hypot(carve.x() - X, carve.z() - Z)).isGreaterThan(SHAPE.room() * 3.0));
    }

    @Test
    @DisplayName("A ravine is cut deeper than a tunnel is tall")
    void ravinesAreDeep() {
        CavePlan.Shape ravinesOnly = new CavePlan.Shape(32, 24, 16, 4, 0, 0, 1, 20, 14);
        CavePlan plan = CavePlan.plan(9L, X, Y, Z, ravinesOnly);

        double low = plan.carves().stream()
                .skip(1)
                .mapToDouble(CavePlan.Carve::y)
                .min()
                .orElseThrow();
        double high = plan.carves().stream()
                .skip(1)
                .mapToDouble(CavePlan.Carve::y)
                .max()
                .orElseThrow();
        assertThat(high - low).isGreaterThanOrEqualTo(10);
    }

    @Test
    @DisplayName("A chunk asks only the carving that reaches it, and gets the same answer as the whole")
    void aChunkAsksItsPart() {
        CavePlan plan = CavePlan.plan(11L, X, Y, Z, SHAPE);
        CavePlan chunk = plan.within(X, X + 15, Z, Z + 15);

        assertThat(chunk.carves().size()).isLessThan(plan.carves().size());
        for (int x = X; x <= X + 15; x++) {
            for (int y = Y - SHAPE.below(); y <= Y + SHAPE.above(); y++) {
                assertThat(chunk.carved(x, y, Z + 7)).isEqualTo(plan.carved(x, y, Z + 7));
            }
        }
    }

    @Test
    @DisplayName("Ores are picked by their chances in the order written, and chances past one are refused")
    void oresArePicked() {
        OreTable table = new OreTable(List.of(OreTable.Ore.parse("coal_ore:0.1"), OreTable.Ore.parse("IRON_ORE:0.05")));

        assertThat(table.pick(0.05)).contains("COAL_ORE");
        assertThat(table.pick(0.12)).contains("IRON_ORE");
        assertThat(table.pick(0.2)).isEmpty();
        assertThatThrownBy(() -> new OreTable(List.of(new OreTable.Ore("A", 0.7), new OreTable.Ore("B", 0.4))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OreTable.Ore.parse("coal_ore")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CavePlan.Shape(32, 24, 16, 20, 0, 0, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
