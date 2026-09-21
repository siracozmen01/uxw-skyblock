package com.uxplima.uxmskyblock.core.application.island;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Makes a read, a change and a write of one island one thing.
 *
 * <p>Nine services read the whole island aggregate, change one part of it and write the whole thing
 * back, and none of them held anything while they did it. Two of those running at once on one
 * island is a lost update that nothing reports and nothing in the database records.
 */
class IslandMutationLockTest {

    private static final IslandId ONE = IslandId.of(UUID.randomUUID());
    private static final IslandId ANOTHER = IslandId.of(UUID.randomUUID());

    @Test
    @DisplayName("A read, a change and a write on one island do not interleave with another's")
    void changesOnOneIslandDoNotInterleave() throws Exception {
        IslandMutationLock lock = new IslandMutationLock();
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger seenTogether = new AtomicInteger();
        int threads = 8;
        int rounds = 200;

        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int t = 0; t < threads; t++) {
                var unused = pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int round = 0; round < rounds; round++) {
                        lock.inside(ONE, () -> {
                            if (inside.incrementAndGet() != 1) {
                                seenTogether.incrementAndGet();
                            }
                            Thread.onSpinWait();
                            inside.decrementAndGet();
                        });
                    }
                });
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(seenTogether)
                .describedAs("two changes to one island were inside the lock at the same time")
                .hasValue(0);
    }

    @Test
    @DisplayName("Two changes that both read before either writes lose one, which is the defect")
    void withoutTheLockAnUpdateIsLost() throws Exception {
        AtomicInteger stored = new AtomicInteger();
        CountDownLatch bothHaveRead = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int t = 0; t < 2; t++) {
                var unused = pool.submit(() -> {
                    // Read, change, write, with nothing held: the shape every island service had.
                    // The latch makes both reads happen before either write, which is the
                    // interleaving the lock exists to prevent. It is forced rather than hoped for,
                    // because a test that waits for a race to turn up is a test that passes on a
                    // quiet machine and proves nothing.
                    int read = stored.get();
                    bothHaveRead.countDown();
                    try {
                        assertThat(bothHaveRead.await(10, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    stored.set(read + 1);
                });
            }
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(stored.get())
                .describedAs("two changes were made and only one of them is there")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("The same read, change and write under the lock loses nothing")
    void underTheLockNothingIsLost() throws Exception {
        IslandMutationLock lock = new IslandMutationLock();
        AtomicInteger stored = new AtomicInteger();
        int threads = 8;
        int rounds = 400;

        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int t = 0; t < threads; t++) {
                var unused = pool.submit(() -> {
                    try {
                        go.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int round = 0; round < rounds; round++) {
                        lock.inside(ONE, () -> {
                            int read = stored.get();
                            Thread.onSpinWait();
                            stored.set(read + 1);
                        });
                    }
                });
            }
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(stored.get()).isEqualTo(threads * rounds);
    }

    @Test
    @DisplayName("Two islands never wait on each other")
    void twoIslandsDoNotWaitOnEachOther() throws Exception {
        IslandMutationLock lock = new IslandMutationLock();
        CountDownLatch bothInside = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (IslandId islandId : List.of(ONE, ANOTHER)) {
                var unused = pool.submit(() -> lock.inside(islandId, () -> {
                    bothInside.countDown();
                    try {
                        // Neither may finish until the other is also inside, which only works if
                        // the two islands hold two different locks.
                        assertThat(bothInside.await(10, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
            pool.shutdown();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS))
                    .describedAs("one island waited for another")
                    .isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("A service that calls another one inside its own change does not deadlock against itself")
    void aNestedChangeDoesNotDeadlock() {
        IslandMutationLock lock = new IslandMutationLock();

        int result = lock.inside(ONE, () -> lock.inside(ONE, () -> 7));

        assertThat(result).isEqualTo(7);
    }

    @Test
    @DisplayName("The lock is released even when the change throws")
    void theLockIsReleasedWhenTheChangeThrows() {
        IslandMutationLock lock = new IslandMutationLock();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> lock.inside(ONE, () -> {
                    throw new IllegalStateException("the island was gone");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(lock.isHeld(ONE)).isFalse();
        assertThat(lock.inside(ONE, () -> "still usable")).isEqualTo("still usable");
    }

    @Test
    @DisplayName("A lock is thrown away when nobody wants it, so the map does not pile up")
    void theLocksDoNotPileUp() {
        IslandMutationLock lock = new IslandMutationLock();

        for (int island = 0; island < 1000; island++) {
            lock.inside(IslandId.of(UUID.randomUUID()), () -> "done");
        }

        assertThat(lock.held())
                .describedAs("a map keyed by island that nothing removes from is a slow leak on a "
                        + "server that has made a hundred thousand islands")
                .isZero();
    }

    @Test
    @DisplayName("A nested change does not drop the lock when the inner one finishes")
    void anestedChangeKeepsTheLock() {
        IslandMutationLock lock = new IslandMutationLock();

        String result = lock.inside(ONE, () -> {
            lock.inside(ONE, () -> "inner");
            assertThat(lock.isHeld(ONE))
                    .describedAs("the outer change is still holding it")
                    .isTrue();
            return "outer";
        });

        assertThat(result).isEqualTo("outer");
        assertThat(lock.held()).isZero();
    }

    @Test
    @DisplayName("Throwing away and handing out at the same time never lets two changes in together")
    void reusingALockNeverLetsTwoIn() throws Exception {
        IslandMutationLock lock = new IslandMutationLock();
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger seenTogether = new AtomicInteger();
        int threads = 8;
        int rounds = 2000;

        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int t = 0; t < threads; t++) {
                var unused = pool.submit(() -> {
                    try {
                        go.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    // Short changes on one island, so the lock is handed out and thrown away
                    // constantly. If a caller could be given a lock that is being removed, two of
                    // them would end up on two different locks and both get in.
                    for (int round = 0; round < rounds; round++) {
                        lock.inside(ONE, () -> {
                            if (inside.incrementAndGet() != 1) {
                                seenTogether.incrementAndGet();
                            }
                            inside.decrementAndGet();
                        });
                    }
                });
            }
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(seenTogether)
                .describedAs("two changes to one island got in together")
                .hasValue(0);
        assertThat(lock.held())
                .describedAs("and the lock was given back afterwards")
                .isZero();
    }

    @Test
    @DisplayName("A change that throws still gives its lock back")
    void athrowingChangeStillGivesItBack() {
        IslandMutationLock lock = new IslandMutationLock();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> lock.inside(ONE, () -> {
                    throw new IllegalStateException("the island was gone");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(lock.held()).isZero();
    }
}
