package com.uxplima.uxmskyblock.core.application.booster;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.uxplima.uxmskyblock.core.domain.booster.BoosterCategory;
import com.uxplima.uxmskyblock.core.domain.booster.CategoryBoosterPolicy;
import com.uxplima.uxmskyblock.core.domain.booster.IslandBooster;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A booster is paused when nobody is on the island and running when somebody is.
 *
 * <p>A join and a quit on one island arrive on the same pool, and both used to read the boosters,
 * decide from a count taken before either of them had written anything, and write. The quit counted
 * nobody left and started pausing; the join counted somebody and found nothing paused yet, so it
 * resumed nothing; the pause then landed on an occupied island. Its boosters stopped applying and
 * stopped counting down, and the island's own record of whether it was paused said the opposite of
 * its rows. The other order burns paid booster time on an empty island, which is the thing
 * pause-when-empty exists to prevent.
 */
class ABoosterFollowsWhoIsActuallyOnTheIslandTest {

    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());
    private static final Instant NOW = Instant.parse("2026-09-21T12:00:00Z");

    /** Holds every reader until they have all read, which is the interleaving the two events make. */
    private static final class SlowBoosterStorage implements IslandBoosterStoragePort {

        private final java.util.Map<UUID, IslandBooster> boosters = new ConcurrentHashMap<>();
        private final CountDownLatch everybodyRead;
        final AtomicInteger writes = new AtomicInteger();

        SlowBoosterStorage(int readers) {
            this.everybodyRead = new CountDownLatch(readers);
        }

        @Override
        public List<IslandBooster> findByIsland(IslandId islandId) {
            List<IslandBooster> held = boosters.values().stream()
                    .filter(booster -> booster.islandId().equals(islandId))
                    .toList();
            everybodyRead.countDown();
            try {
                var unused = everybodyRead.await(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return held;
        }

        @Override
        public void saveBooster(IslandBooster booster) {
            boosters.put(booster.id(), booster);
            writes.incrementAndGet();
        }

        @Override
        public void saveAll(Collection<IslandBooster> toSave) {
            for (IslandBooster booster : toSave) {
                saveBooster(booster);
            }
        }

        @Override
        public Optional<IslandBooster> findById(UUID boosterId) {
            return Optional.ofNullable(boosters.get(boosterId));
        }

        @Override
        public List<IslandBooster> findByIslandAndCategory(IslandId islandId, BoosterCategory category) {
            return boosters.values().stream()
                    .filter(booster -> booster.islandId().equals(islandId) && booster.category() == category)
                    .toList();
        }

        @Override
        public void deleteById(UUID boosterId) {
            boosters.remove(boosterId);
        }

        @Override
        public void deleteByIsland(IslandId islandId) {
            boosters.values().removeIf(booster -> booster.islandId().equals(islandId));
        }

        @Override
        public int purgeExpired(Instant now) {
            return 0;
        }
    }

    private static IslandBooster running() {
        return new IslandBooster(
                UUID.randomUUID(),
                ISLAND,
                BoosterCategory.CROP_GROWTH,
                2.0,
                NOW.plus(Duration.ofHours(4)),
                NOW,
                null,
                0L);
    }

    private static IslandBoosterService serviceOver(IslandBoosterStoragePort storage) {
        return new IslandBoosterService(storage, CategoryBoosterPolicy::defaultFor, true);
    }

    @Test
    @DisplayName("A quit and a join at the same moment leave the booster running, because somebody is on")
    void somebodyOnLeavesItRunning() throws Exception {
        SlowBoosterStorage storage = new SlowBoosterStorage(2);
        storage.saveBooster(running());
        IslandBoosterService service = serviceOver(storage);

        // One member is on. One of them quits and another joins in the same second, so the island is
        // never actually empty. Whichever of the two runs second must be the one that decides, and
        // what it reads is this count, which is the server rather than a number taken earlier.
        AtomicInteger online = new AtomicInteger(1);
        runTogether(
                () -> {
                    online.decrementAndGet();
                    service.followOccupancy(ISLAND, online::get, NOW);
                },
                () -> {
                    online.incrementAndGet();
                    service.followOccupancy(ISLAND, online::get, NOW);
                });

        assertThat(online.get()).describedAs("members left on the island").isEqualTo(1);
        assertThat(service.isIslandPaused(ISLAND))
                .describedAs("an island somebody is standing on must not have its boosters frozen")
                .isFalse();
        assertThat(storage.findByIsland(ISLAND))
                .describedAs("and its rows must say the same")
                .noneMatch(IslandBooster::isPaused);
    }

    @Test
    @DisplayName("Two quits that really do empty the island leave the booster paused")
    void nobodyOnLeavesItPaused() throws Exception {
        SlowBoosterStorage storage = new SlowBoosterStorage(2);
        storage.saveBooster(running());
        IslandBoosterService service = serviceOver(storage);

        AtomicInteger online = new AtomicInteger(2);
        Runnable quit = () -> {
            online.decrementAndGet();
            service.followOccupancy(ISLAND, online::get, NOW);
        };
        runTogether(quit, quit);

        assertThat(online.get()).describedAs("members left on the island").isZero();
        assertThat(service.isIslandPaused(ISLAND))
                .describedAs("an empty island must not burn booster time")
                .isTrue();
        assertThat(storage.findByIsland(ISLAND))
                .describedAs("and its rows must say the same")
                .allMatch(IslandBooster::isPaused);
    }

    @Test
    @DisplayName("What the island says about being paused is what its rows say")
    void theRecordAndTheRowsAgree() throws Exception {
        SlowBoosterStorage storage = new SlowBoosterStorage(2);
        storage.saveBooster(running());
        IslandBoosterService service = serviceOver(storage);

        AtomicInteger online = new AtomicInteger(1);
        runTogether(
                () -> {
                    online.decrementAndGet();
                    service.followOccupancy(ISLAND, online::get, NOW);
                },
                () -> {
                    online.incrementAndGet();
                    service.followOccupancy(ISLAND, online::get, NOW);
                });

        boolean anyRowPaused = storage.findByIsland(ISLAND).stream().anyMatch(IslandBooster::isPaused);
        assertThat(service.isIslandPaused(ISLAND))
                .describedAs("the service's answer against its own rows")
                .isEqualTo(anyRowPaused);
    }

    @Test
    @DisplayName("The count is taken inside the lock, so two of them are never counting at once")
    void theCountIsTakenInsideTheLock() throws Exception {
        SlowBoosterStorage storage = new SlowBoosterStorage(2);
        storage.saveBooster(running());
        IslandBoosterService service = serviceOver(storage);

        // Both tasks try to be counting at the same moment. Under the lock exactly one of them can
        // be, so the most that are ever counting together is one. Taken before the lock, as it used
        // to be, both count together and the most is two: two decisions from one moment, and the
        // second of them already out of date when it is written.
        AtomicInteger counting = new AtomicInteger();
        AtomicInteger mostAtOnce = new AtomicInteger();
        CountDownLatch bothIn = new CountDownLatch(2);
        java.util.function.IntSupplier count = () -> {
            mostAtOnce.accumulateAndGet(counting.incrementAndGet(), Math::max);
            bothIn.countDown();
            try {
                var unused = bothIn.await(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            counting.decrementAndGet();
            return 1;
        };

        runTogether(
                () -> service.followOccupancy(ISLAND, count, NOW), () -> service.followOccupancy(ISLAND, count, NOW));

        assertThat(mostAtOnce.get())
                .describedAs("counts being taken at the same moment, which is two decisions from "
                        + "one instant and the second of them stale before it is written")
                .isOne();
    }

    private static void runTogether(Runnable first, Runnable second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<?>> running = new ArrayList<>();
        try {
            for (Runnable task : List.of(first, second)) {
                running.add(pool.submit(() -> {
                    try {
                        var unused = start.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    task.run();
                }));
            }
            start.countDown();
            for (Future<?> task : running) {
                task.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
