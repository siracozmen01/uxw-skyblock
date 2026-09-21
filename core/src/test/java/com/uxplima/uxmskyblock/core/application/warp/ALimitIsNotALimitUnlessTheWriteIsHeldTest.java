package com.uxplima.uxmskyblock.core.application.warp;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.warp.IslandBan;
import com.uxplima.uxmskyblock.core.domain.warp.IslandWarp;
import com.uxplima.uxmskyblock.core.domain.warp.WarpCategory;
import com.uxplima.uxmskyblock.core.domain.warp.WarpLocation;
import com.uxplima.uxmskyblock.core.domain.warp.WarpName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An island never ends up with more warps than its upgrade allows.
 *
 * <p>The count is read, compared to the limit, and then a warp is written. Two of those at once
 * both read one below the limit, both pass, and both write. The island gets a warp it did not pay
 * for, and every player on the server can do it by typing the command twice quickly.
 */
class ALimitIsNotALimitUnlessTheWriteIsHeldTest {

    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());
    private static final ProfileId OWNER = ProfileId.of(UUID.randomUUID());
    private static final int ALLOWED = 3;

    private SlowCountingWarpStorage storage;
    private IslandWarpService warps;
    private Island island;

    /**
     * A storage whose count is slow, which is what a database is.
     *
     * <p>The pause between counting and writing is the window. A real one is a round trip; this one
     * is a spin, so the race turns up on every machine rather than on a busy one.
     */
    private static final class SlowCountingWarpStorage implements IslandWarpStoragePort {
        private final List<IslandWarp> warps = java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public int countWarpsByIsland(IslandId islandId) {
            int count = warps.size();
            for (int spin = 0; spin < 2000; spin++) {
                Thread.onSpinWait();
            }
            return count;
        }

        @Override
        public void saveWarp(IslandWarp warp) {
            warps.add(warp);
        }

        int size() {
            return warps.size();
        }

        @Override
        public Optional<IslandWarp> findWarpById(com.uxplima.uxmskyblock.core.domain.warp.IslandWarpId id) {
            return Optional.empty();
        }

        @Override
        public Optional<IslandWarp> findWarpByName(IslandId islandId, WarpName name) {
            return Optional.empty();
        }

        @Override
        public List<IslandWarp> findWarpsByIsland(IslandId islandId) {
            return List.copyOf(warps);
        }

        @Override
        public boolean deleteWarp(IslandId islandId, WarpName warpName) {
            return false;
        }

        @Override
        public List<IslandWarp> findPublicWarps(int limit, int offset) {
            return List.of();
        }

        @Override
        public List<IslandWarp> findPublicWarpsByCategory(WarpCategory category, int limit, int offset) {
            return List.of();
        }

        @Override
        public void banPlayer(IslandBan ban) {}

        @Override
        public boolean unbanPlayer(IslandId islandId, PlayerUuid playerUuid) {
            return false;
        }

        @Override
        public boolean isPlayerBanned(IslandId islandId, PlayerUuid playerUuid) {
            return false;
        }

        @Override
        public List<IslandBan> findBansByIsland(IslandId islandId) {
            return List.of();
        }
    }

    @BeforeEach
    void setUp() {
        island = Island.create(
                ISLAND,
                IslandBounds.fromCenterAndRadius(0, 0, 100),
                PlayerUuid.of(UUID.randomUUID()),
                OWNER,
                Instant.now());
        storage = new SlowCountingWarpStorage();
        warps = new IslandWarpService(storage, new SafeTeleportEngine(), null, ALLOWED, null);
    }

    @Test
    @DisplayName("Eight players creating a warp at once leave the island with the three it is allowed")
    void eightAtOnceLeaveTheAllowedThree() throws Exception {
        AtomicInteger created = new AtomicInteger();
        int threads = 8;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int t = 0; t < threads; t++) {
                int which = t;
                var unused = pool.submit(() -> {
                    try {
                        go.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    try {
                        warps.createWarp(
                                island,
                                OWNER,
                                WarpName.of("warp" + which),
                                new WarpLocation("world", 5.0, 64.0, 5.0, 0.0f, 0.0f),
                                WarpCategory.GENERAL,
                                "OAK_SIGN");
                        created.incrementAndGet();
                    } catch (RuntimeException refused) {
                        // The limit said no, which is the whole point.
                    }
                });
            }
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(storage.size())
                .describedAs("an island with more warps than its upgrade allows is a warp nobody paid for")
                .isEqualTo(ALLOWED);
        assertThat(created).hasValue(ALLOWED);
    }

    @Test
    @DisplayName("One at a time still gets exactly what it is allowed")
    void oneAtATimeGetsTheAllowed() {
        for (int i = 0; i < 10; i++) {
            try {
                warps.createWarp(
                        island,
                        OWNER,
                        WarpName.of("warp" + i),
                        new WarpLocation("world", 5.0, 64.0, 5.0, 0.0f, 0.0f),
                        WarpCategory.GENERAL,
                        "OAK_SIGN");
            } catch (RuntimeException refused) {
                // expected once the limit is reached
            }
        }

        assertThat(storage.size()).isEqualTo(ALLOWED);
    }
}
