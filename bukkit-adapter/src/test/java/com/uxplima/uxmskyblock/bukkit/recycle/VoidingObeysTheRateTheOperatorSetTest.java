package com.uxplima.uxmskyblock.bukkit.recycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;

import com.uxplima.uxmskyblock.core.application.performance.AdaptiveBackpressureController;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Voiding an island starts no more chunks at once than the operator allows.
 *
 * <p>Every chunk of the island was handed to its region at once. An island two hundred blocks a
 * side is a hundred and sixty nine of them, each clearing every block in its own column, all
 * landing on the same tick. The operator's file has named a rate for this since the performance
 * work, one number for an ordinary server and a smaller one for a server already behind, and the
 * adapter held the controller that answers it and never asked.
 */
class VoidingObeysTheRateTheOperatorSetTest {

    private Server server;
    private World world;

    /** Records how the work was paced: one entry per batch, holding how many chunks it started. */
    private static final class PacingScheduler implements SchedulerPort {
        private final List<Integer> batches = new ArrayList<>();
        private final List<Runnable> waiting = new ArrayList<>();
        private int startedInThisBatch;

        /** The running total of blocks cleared, as it stood at the start of each region task. */
        private final List<Integer> sliceStarts = new ArrayList<>();

        java.util.function.IntSupplier clearedSoFar = () -> 0;

        @Override
        public void onRegion(String worldName, int chunkX, int chunkZ, Runnable task) {
            startedInThisBatch++;
            sliceStarts.add(clearedSoFar.getAsInt());
            task.run();
        }

        /** How much each region task cleared before handing the region back. */
        List<Integer> sliceSizes() {
            List<Integer> sizes = new ArrayList<>();
            List<Integer> points = new ArrayList<>(sliceStarts);
            points.add(clearedSoFar.getAsInt());
            for (int i = 1; i < points.size(); i++) {
                sizes.add(points.get(i) - points.get(i - 1));
            }
            return sizes;
        }

        @Override
        public void asyncAfter(Duration delay, Runnable task) {
            batches.add(startedInThisBatch);
            startedInThisBatch = 0;
            waiting.add(task);
        }

        /** Runs everything that was told to wait, recording each batch as it goes. */
        void runEverythingThatWaited() {
            while (!waiting.isEmpty()) {
                Runnable next = waiting.remove(0);
                next.run();
            }
            batches.add(startedInThisBatch);
            startedInThisBatch = 0;
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
    void setUp() throws Exception {
        server = mock(Server.class);
        setBukkitServer(server);

        world = mock(World.class);
        when(server.getWorld("skyblock_world")).thenReturn(world);
        when(world.getMinHeight()).thenReturn(0);
        when(world.getMaxHeight()).thenReturn(2);
        when(world.getSpawnLocation()).thenReturn(new Location(world, 0, 64, 0));

        Chunk chunk = mock(Chunk.class);
        when(world.getChunkAt(anyInt(), anyInt())).thenReturn(chunk);
        when(chunk.getEntities()).thenReturn(new Entity[0]);

        Block block = mock(Block.class);
        when(block.isEmpty()).thenReturn(true);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(block);
    }

    @AfterEach
    void tearDown() throws Exception {
        setBukkitServer(null);
    }

    private static void setBukkitServer(@Nullable Server s) throws Exception {
        Field field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);
        field.set(null, s);
    }

    /** One chunk, so the only thing being paced is the blocks inside it. */
    private static IslandBounds oneChunkWide() {
        return IslandBounds.fromCenterAndRadius(8, 8, 3);
    }

    /** An island covering sixteen chunks, so a rate below sixteen has something to hold back. */
    private static IslandBounds sixteenChunksWide() {
        return IslandBounds.fromCenterAndRadius(0, 0, 20);
    }

    private static AdaptiveBackpressureController rateOf(int chunksPerSecond, boolean behind) {
        return new AdaptiveBackpressureController(
                () -> behind ? 10.0 : 20.0, true, 19.5, 128, 16, chunksPerSecond, chunksPerSecond / 2);
    }

    @Test
    @DisplayName("No more chunks are started at once than the rate allows")
    void thefirstBatchIsNoLargerThanTheRate() {
        PacingScheduler scheduler = new PacingScheduler();
        FoliaIslandVoidingAdapter adapter = new FoliaIslandVoidingAdapter(scheduler, rateOf(2, false));

        var unused = adapter.voidIslandChunks(IslandId.of(UUID.randomUUID()), "skyblock_world", sixteenChunksWide());
        scheduler.runEverythingThatWaited();

        assertThat(scheduler.batches)
                .describedAs("every chunk of the island used to land on the same tick")
                .isNotEmpty();
        assertThat(scheduler.batches).allSatisfy(started -> assertThat(started).isLessThanOrEqualTo(2));
        assertThat(scheduler.batches.stream().mapToInt(Integer::intValue).sum())
                .describedAs("and all of them are still cleared")
                .isEqualTo(16);
    }

    @Test
    @DisplayName("A server already behind clears fewer chunks a second than one that is not")
    void aserverBehindGoesSlower() {
        PacingScheduler fast = new PacingScheduler();
        var unusedFast = new FoliaIslandVoidingAdapter(fast, rateOf(8, false))
                .voidIslandChunks(IslandId.of(UUID.randomUUID()), "skyblock_world", sixteenChunksWide());
        fast.runEverythingThatWaited();

        PacingScheduler slow = new PacingScheduler();
        var unusedSlow = new FoliaIslandVoidingAdapter(slow, rateOf(8, true))
                .voidIslandChunks(IslandId.of(UUID.randomUUID()), "skyblock_world", sixteenChunksWide());
        slow.runEverythingThatWaited();

        assertThat(fast.batches.get(0))
                .describedAs("the number the operator set for an ordinary server")
                .isEqualTo(8);
        assertThat(slow.batches.get(0))
                .describedAs("and the smaller one for a server already behind")
                .isEqualTo(4);
    }

    @Test
    @DisplayName("No tick clears more blocks than the operator allows")
    void aticksWorthOfClearingIsWhatTheOperatorSet() {
        // Every block in range is occupied, so every position costs one write.
        java.util.concurrent.atomic.AtomicInteger cleared = new java.util.concurrent.atomic.AtomicInteger();
        Block occupied = mock(Block.class);
        when(occupied.isEmpty()).thenReturn(false);
        org.mockito.Mockito.doAnswer(invocation -> {
                    cleared.incrementAndGet();
                    return null;
                })
                .when(occupied)
                .setType(
                        org.mockito.ArgumentMatchers.any(org.bukkit.Material.class),
                        org.mockito.ArgumentMatchers.anyBoolean());
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(occupied);

        PacingScheduler scheduler = new PacingScheduler();
        scheduler.clearedSoFar = cleared::get;
        AdaptiveBackpressureController controller =
                new AdaptiveBackpressureController(() -> 20.0, true, 19.5, 4, 2, 100, 50);
        FoliaIslandVoidingAdapter adapter = new FoliaIslandVoidingAdapter(scheduler, controller);

        var unused = adapter.voidIslandChunks(IslandId.of(UUID.randomUUID()), "skyblock_world", oneChunkWide());
        scheduler.runEverythingThatWaited();

        assertThat(scheduler.sliceSizes())
                .describedAs("a chunk column used to be cleared on one tick, however many blocks it held")
                .isNotEmpty()
                .allSatisfy(written -> assertThat(written).isLessThanOrEqualTo(4));
        assertThat(cleared.get())
                .describedAs("and every block in range is still cleared")
                .isPositive();
    }

    @Test
    @DisplayName("A node with no controller clears the island as fast as it can, in one go")
    void nocontrollerMeansNoWaiting() {
        PacingScheduler scheduler = new PacingScheduler();
        FoliaIslandVoidingAdapter adapter = new FoliaIslandVoidingAdapter(scheduler);

        var unused = adapter.voidIslandChunks(IslandId.of(UUID.randomUUID()), "skyblock_world", sixteenChunksWide());
        scheduler.runEverythingThatWaited();

        assertThat(scheduler.batches).containsExactly(16);
    }
}
