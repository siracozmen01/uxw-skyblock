package com.uxplima.uxmskyblock.bukkit.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.dimension.DimensionId;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeInstanceId;
import com.uxplima.uxmskyblock.core.domain.gamemode.PrimaryGameplayRootRef;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * A capture reads each chunk inside the region that owns it, and gives up whole on a region that
 * never answers.
 *
 * <p>The testing standard names this test. An island spans several regions and each one's chunks are
 * read by a task handed to that region; what leaves the task is coordinates and block strings, never a
 * live block. A region that never runs its task used to hold the capture open for ever, and the
 * backup that asked for it never answered: the capture now stops at its timeout, writes nothing, and
 * a chunk that arrives late lands nowhere.
 */
class SnapshotRegionThreadIsolationTest {

    private final UUID island = UUID.randomUUID();
    private final Map<String, String> regionThreads = new ConcurrentHashMap<>();
    private final List<String> heldBack = new CopyOnWriteArrayList<>();
    private final List<Runnable> heldTasks = new CopyOnWriteArrayList<>();
    private ExecutorService regions = Executors.newCachedThreadPool();

    @SuppressWarnings("NullAway.Init")
    private World world;

    @BeforeEach
    void setUp() {
        world = MockBukkit.mock().addSimpleWorld("world");
        world.getBlockAt(-3, 64, -3).setType(Material.DIAMOND_BLOCK, false);
        world.getBlockAt(3, 64, 3).setType(Material.EMERALD_BLOCK, false);
        regions = Executors.newCachedThreadPool();
    }

    @AfterEach
    void tearDown() {
        regions.shutdownNow();
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("Every chunk of a multi-region island is read in the task its own region was given")
    void everyChunkIsReadInItsRegion() {
        WorldDimensionSnapshotAdapter adapter = adapter(Set.of());

        byte[] payload = adapter.captureWorldDimension(rootRef(), DimensionId.OVERWORLD);

        // Bounds -4..4 cover chunks -1 and 0 on both axes: four regions.
        assertThat(regionThreads)
                .containsOnlyKeys("-1,-1", "-1,0", "0,-1", "0,0")
                .allSatisfy((region, thread) -> assertThat(thread).isEqualTo("region " + region));
        world.getBlockAt(-3, 64, -3).setType(Material.AIR, false);
        world.getBlockAt(3, 64, 3).setType(Material.AIR, false);
        // Put back inline, so what is checked is what the four regions' tasks captured.
        IslandStoragePort islands = mock(IslandStoragePort.class);
        when(islands.findLocationByIslandId(any()))
                .thenReturn(Optional.of(IslandLocation.fromCenterAndRadius(IslandId.of(island), "world", 0, 0, 4)));
        new WorldDimensionSnapshotAdapter(mock(Plugin.class), islands)
                .restoreWorldDimension(rootRef(), DimensionId.OVERWORLD, payload);
        assertThat(world.getBlockAt(-3, 64, -3).getType()).isEqualTo(Material.DIAMOND_BLOCK);
        assertThat(world.getBlockAt(3, 64, 3).getType()).isEqualTo(Material.EMERALD_BLOCK);
    }

    @Test
    @DisplayName(
            "A region that never answers ends the capture at its timeout with nothing written, and its late chunks land nowhere")
    @org.junit.jupiter.api.Timeout(10)
    void aSilentRegionEndsTheCapture() {
        WorldDimensionSnapshotAdapter adapter = adapter(Set.of("0,0"));
        adapter.captureWithin(Duration.ofMillis(500));

        long started = System.nanoTime();
        assertThatThrownBy(() -> adapter.captureWorldDimension(rootRef(), DimensionId.OVERWORLD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("did not hand its chunks over");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        assertThat(heldBack).containsExactly("0,0");

        // The region answers after all. Nothing is waiting for it, and nothing it read is written.
        heldTasks.forEach(Runnable::run);
        WorldDimensionSnapshotAdapter next = adapter(Set.of());
        assertThat(next.captureWorldDimension(rootRef(), DimensionId.OVERWORLD)).isNotEmpty();
    }

    @Test
    @DisplayName("A capture timeout that is not positive is refused")
    void aTimeoutMustBePositive() {
        assertThatThrownBy(() -> adapter(Set.of()).captureWithin(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** An adapter whose regions each run on a thread of their own, except those that never answer. */
    private WorldDimensionSnapshotAdapter adapter(Set<String> silent) {
        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(call -> {
                    String region = call.getArgument(1, Integer.class) + "," + call.getArgument(2, Integer.class);
                    Runnable task = call.getArgument(3, Runnable.class);
                    if (silent.contains(region)) {
                        heldBack.add(region);
                        heldTasks.add(task);
                        return null;
                    }
                    // On a thread of the region's own, one region at a time: the mock world under
                    // this test is not safe to read from two threads at once, a real region is.
                    regions.submit(() -> {
                                Thread.currentThread().setName("region " + region);
                                regionThreads.put(region, Thread.currentThread().getName());
                                task.run();
                            })
                            .get();
                    return null;
                })
                .when(scheduler)
                .onRegion(anyString(), anyInt(), anyInt(), any(Runnable.class));
        IslandStoragePort islands = mock(IslandStoragePort.class);
        when(islands.findLocationByIslandId(any()))
                .thenReturn(Optional.of(IslandLocation.fromCenterAndRadius(IslandId.of(island), "world", 0, 0, 4)));
        return new WorldDimensionSnapshotAdapter(mock(Plugin.class), islands, scheduler, null);
    }

    private PrimaryGameplayRootRef rootRef() {
        return new PrimaryGameplayRootRef(
                GameModeInstanceId.of(UUID.randomUUID()), island.toString(), "ISLAND", Instant.now());
    }
}
