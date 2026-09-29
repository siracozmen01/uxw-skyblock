package com.uxplima.uxmskyblock.bukkit.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.uxplima.uxmskyblock.bukkit.spatial.SpatialIslandIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The tick rate the health endpoint reads, on a server that has one and on Folia, which has none. */
class BukkitServerHealthAdapterTest {

    @Test
    @DisplayName("The last minute's rate is read where the server keeps one")
    void aServerWithARate() {
        BukkitServerHealthAdapter adapter =
                new BukkitServerHealthAdapter(mock(SpatialIslandIndex.class), () -> new double[] {19.5, 19.9, 20.0});

        assertThat(adapter.ticksPerSecond()).isEqualTo(19.5);
    }

    @Test
    @DisplayName("Folia refuses the question off a region's thread, and the answer is that there is none")
    void foliaHasNone() {
        BukkitServerHealthAdapter adapter = new BukkitServerHealthAdapter(mock(SpatialIslandIndex.class), () -> {
            throw new UnsupportedOperationException("Not on any region");
        });

        assertThat(adapter.ticksPerSecond()).isNaN();
    }
}
