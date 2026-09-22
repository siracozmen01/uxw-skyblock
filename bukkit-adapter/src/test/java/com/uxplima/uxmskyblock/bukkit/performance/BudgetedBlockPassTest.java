package com.uxplima.uxmskyblock.bukkit.performance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A walk over a chunk's blocks spends only what the operator allows on one tick.
 *
 * <p>Clearing an island and putting one back both walked every block between two corners in a
 * single region task, so one chunk column was tens of thousands of writes landing on one tick. The
 * budget for that is in the operator's file and nothing read it.
 */
class BudgetedBlockPassTest {

    /** Runs a handed-back slice in place, and counts how many times the walk handed one back. */
    private static final class CountingScheduler implements SchedulerPort {
        private final List<Runnable> pending = new ArrayList<>();
        private int handBacks;

        @Override
        public void onRegion(String worldName, int chunkX, int chunkZ, Runnable task) {
            handBacks++;
            pending.add(task);
        }

        void drain() {
            while (!pending.isEmpty()) {
                pending.remove(0).run();
            }
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

    @Test
    @DisplayName("Every position between the corners is visited exactly once")
    void everypositionIsVisitedOnce() {
        CountingScheduler scheduler = new CountingScheduler();
        List<String> seen = new ArrayList<>();

        CompletableFuture<Void> done =
                BudgetedBlockPass.over(scheduler, "world", 0, 0, 0, 2, 0, 1, 0, 3, () -> 2, (x, y, z) -> {
                    seen.add(x + "," + y + "," + z);
                    return true;
                });
        scheduler.drain();

        assertThat(done).isCompleted();
        assertThat(seen).hasSize(3 * 2 * 3).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("No slice writes more than the budget allows")
    void noSliceOverspendsTheBudget() {
        CountingScheduler scheduler = new CountingScheduler();
        AtomicInteger inThisSlice = new AtomicInteger();
        List<Integer> slices = new ArrayList<>();

        CompletableFuture<Void> done = BudgetedBlockPass.over(
                scheduler,
                "world",
                0,
                0,
                0,
                3,
                0,
                3,
                0,
                4,
                () -> {
                    if (inThisSlice.get() > 0) {
                        slices.add(inThisSlice.getAndSet(0));
                    }
                    return 5;
                },
                (x, y, z) -> {
                    inThisSlice.incrementAndGet();
                    return true;
                });
        scheduler.drain();
        slices.add(inThisSlice.get());

        assertThat(done).isCompleted();
        assertThat(slices).allSatisfy(written -> assertThat(written).isLessThanOrEqualTo(5));
        assertThat(slices.stream().mapToInt(Integer::intValue).sum()).isEqualTo(4 * 4 * 4);
    }

    @Test
    @DisplayName("A position that writes nothing costs nothing, so air is walked in one go")
    void lookingCostsNothing() {
        CountingScheduler scheduler = new CountingScheduler();

        CompletableFuture<Void> done =
                BudgetedBlockPass.over(scheduler, "world", 0, 0, 0, 7, 0, 7, 0, 100, () -> 4, (x, y, z) -> false);

        assertThat(done)
                .describedAs("the budget is spent on writes, and looking at a block that is already "
                        + "what it should be is not one")
                .isCompleted();
        assertThat(scheduler.handBacks).isZero();
    }

    @Test
    @DisplayName("A budget that shrinks halfway through is the one the rest of the walk gets")
    void thebudgetIsReadAgainEverySlice() {
        CountingScheduler scheduler = new CountingScheduler();
        AtomicInteger asked = new AtomicInteger();

        CompletableFuture<Void> done = BudgetedBlockPass.over(
                scheduler,
                "world",
                0,
                0,
                0,
                1,
                0,
                1,
                0,
                8,
                () -> asked.incrementAndGet() == 1 ? 8 : 2,
                (x, y, z) -> true);
        scheduler.drain();

        assertThat(done).isCompleted();
        assertThat(asked)
                .describedAs("a server that falls behind halfway through finishes the rest slower")
                .hasValueGreaterThan(2);
    }

    @Test
    @DisplayName("Work that throws ends the walk rather than leaving it half done and silent")
    void athrowingWorkerEndsTheWalk() {
        CountingScheduler scheduler = new CountingScheduler();

        CompletableFuture<Void> done =
                BudgetedBlockPass.over(scheduler, "world", 0, 0, 0, 1, 0, 1, 0, 4, () -> 100, (x, y, z) -> {
                    throw new IllegalStateException("the chunk went away");
                });

        assertThat(done).isCompletedExceptionally();
    }
}
