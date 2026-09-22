package com.uxplima.uxmskyblock.bukkit.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;

import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.performance.AdaptiveBackpressureController;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.dimension.DimensionId;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeInstanceId;
import com.uxplima.uxmskyblock.core.domain.gamemode.PrimaryGameplayRootRef;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * Putting an island back is paced the way clearing one is.
 *
 * <p>Restoring wrote every block of a chunk's column in a single region task, and an island is a
 * hundred and more chunks of that. The adapter had no backpressure controller at all, so two of the
 * five numbers in the operator's performance file did nothing anywhere.
 */
class RestoringAnIslandIsPacedTest {

    private ServerMock server;
    private Plugin plugin;
    private World world;

    /**
     * Runs a handed-back slice in place, and remembers how many times the walk handed one back.
     *
     * <p>In place rather than queued, because a restore waits for its chunks before it returns: a
     * scheduler that holds the work until afterwards would wait for itself. The walk loops rather
     * than nests when its scheduler runs it in place, so this is safe.
     */
    private static final class CountingScheduler implements SchedulerPort {
        private int handBacks;

        @Override
        public void onRegion(String worldName, int chunkX, int chunkZ, Runnable task) {
            handBacks++;
            task.run();
        }

        @Override
        public void onGlobal(Runnable task) {
            task.run();
        }

        @Override
        public void onEntity(PlayerUuid playerUuid, Runnable task) {
            task.run();
        }

        @Override
        public void async(Runnable task) {
            task.run();
        }

        @Override
        public void asyncAfter(Duration delay, Runnable task) {
            task.run();
        }

        @Override
        public void laterGlobal(Duration delay, Runnable task) {
            task.run();
        }

        @Override
        public AutoCloseable repeatGlobal(Runnable task, Duration initialDelay, Duration period) {
            return () -> {};
        }

        @Override
        public AutoCloseable repeatAsync(Runnable task, Duration initialDelay, Duration period) {
            return () -> {};
        }
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        world = server.addSimpleWorld("world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static AdaptiveBackpressureController budgetOf(int blocksPerTick) {
        return new AdaptiveBackpressureController(() -> 20.0, true, 19.5, blocksPerTick, blocksPerTick, 100, 50);
    }

    /** Restores one island through this scheduler and gives back how often a region was asked for. */
    private int handBacksRestoringWith(@org.jspecify.annotations.Nullable AdaptiveBackpressureController controller) {
        Block placed = world.getBlockAt(2, 64, 3);
        placed.setType(Material.DIAMOND_BLOCK, false);
        Block second = world.getBlockAt(0, 70, 0);
        second.setType(Material.GOLD_BLOCK, false);

        UUID islandUuid = UUID.randomUUID();
        IslandStoragePort storagePort = mock(IslandStoragePort.class);
        when(storagePort.findLocationByIslandId(any()))
                .thenReturn(
                        Optional.of(IslandLocation.fromCenterAndRadius(IslandId.of(islandUuid), "world", 0, 0, 16)));

        CountingScheduler scheduler = new CountingScheduler();
        WorldDimensionSnapshotAdapter adapter =
                new WorldDimensionSnapshotAdapter(plugin, storagePort, scheduler, null, controller);

        PrimaryGameplayRootRef rootRef = new PrimaryGameplayRootRef(
                GameModeInstanceId.of(UUID.randomUUID()), islandUuid.toString(), "ISLAND", Instant.now());
        byte[] payload = adapter.captureWorldDimension(rootRef, DimensionId.OVERWORLD);

        placed.setType(Material.AIR, false);
        second.setType(Material.AIR, false);

        adapter.restoreWorldDimension(rootRef, DimensionId.OVERWORLD, payload);

        assertThat(world.getBlockAt(2, 64, 3).getType())
                .describedAs("the island is put back either way")
                .isEqualTo(Material.DIAMOND_BLOCK);
        assertThat(world.getBlockAt(0, 70, 0).getType()).isEqualTo(Material.GOLD_BLOCK);
        return scheduler.handBacks;
    }

    @Test
    @DisplayName("A restore hands the region back rather than writing the whole column on one tick")
    void arestoreHandsTheRegionBack() {
        int unpaced = handBacksRestoringWith(null);
        int paced = handBacksRestoringWith(budgetOf(1));

        assertThat(paced)
                .describedAs("a chunk column used to be written on one tick, however many blocks it "
                        + "held, and a node with no controller still does")
                .isGreaterThan(unpaced);
    }

    @Test
    @DisplayName("A budget nobody reaches paces nothing, which is what a node with no controller gets")
    void abudgetNobodyReachesPacesNothing() {
        int unpaced = handBacksRestoringWith(null);
        int generous = handBacksRestoringWith(budgetOf(Integer.MAX_VALUE));

        assertThat(generous)
                .describedAs("the only asks left are the one each chunk needs to start at all")
                .isEqualTo(unpaced);
    }
}
