package com.uxplima.uxmskyblock.core.application.home;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.uxplima.uxmskyblock.core.domain.home.Home;
import com.uxplima.uxmskyblock.core.domain.home.HomeLimitPolicy;
import com.uxplima.uxmskyblock.core.domain.home.HomeScope;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A player never keeps more homes than their permission tier allows.
 *
 * <p>The homes are counted, the count is compared to the allowance, and then one is written.
 * Nothing held between the two means a player with two clients types the command twice and keeps
 * one more than they are allowed, and the count is the only thing standing in the way.
 */
class TheHomeAllowanceHoldsUnderTwoClientsTest {

    private static final ProfileId PLAYER = ProfileId.of(UUID.randomUUID());
    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());
    private static final int ALLOWANCE = 3;

    /**
     * A storage where every counter waits for the others before any of them writes.
     *
     * <p>That is the interleaving the lock exists to prevent, forced rather than hoped for. Under
     * the lock only one caller can be counting at a time, so the wait simply times out and the
     * others go in turn: the lock is what makes the window impossible, and the test says so either
     * way rather than depending on how busy the machine is.
     */
    private static final class SlowCountingHomeStorage implements HomeStoragePort {
        private final List<Home> homes = java.util.Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch everybodyCounted;

        SlowCountingHomeStorage(int counters) {
            this.everybodyCounted = new CountDownLatch(counters);
        }

        @Override
        public void saveHome(Home home) {
            homes.removeIf(existing -> existing.ownerProfileId().equals(home.ownerProfileId())
                    && existing.name().equals(home.name()));
            homes.add(home);
        }

        @Override
        public Optional<Home> findHome(ProfileId profileId, String name) {
            synchronized (homes) {
                return homes.stream()
                        .filter(h ->
                                h.ownerProfileId().equals(profileId) && h.name().equals(name))
                        .findFirst();
            }
        }

        @Override
        public List<Home> findHomesByProfileId(ProfileId profileId) {
            return List.copyOf(homes);
        }

        @Override
        public List<Home> findHomesByIslandId(IslandId islandId) {
            return List.copyOf(homes);
        }

        @Override
        public boolean deleteHome(ProfileId profileId, String name) {
            return homes.removeIf(
                    h -> h.ownerProfileId().equals(profileId) && h.name().equals(name));
        }

        @Override
        public int countHomes(ProfileId profileId) {
            int count = homes.size();
            everybodyCounted.countDown();
            try {
                var unused = everybodyCounted.await(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return count;
        }

        int size() {
            return homes.size();
        }
    }

    private static HomeService.SetHomeResult set(HomeService homes, String name) {
        return homes.setHome(PLAYER, ISLAND, name, HomeScope.PERSONAL, "world", 0.0, 64.0, 0.0, 0.0f, 0.0f, ALLOWANCE);
    }

    @Test
    @DisplayName("Eight clients saving a home at once leave the player with the three they are allowed")
    void eightAtOnceLeaveTheAllowedThree() throws Exception {
        int threads = 8;
        SlowCountingHomeStorage storage = new SlowCountingHomeStorage(threads);
        HomeService homes = new HomeService(storage, HomeLimitPolicy.defaultPolicy());

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
                    var ignored = set(homes, "home" + which);
                });
            }
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(storage.size())
                .describedAs("a home over the allowance is one the player's permission tier never gave them")
                .isEqualTo(ALLOWANCE);
    }

    @Test
    @DisplayName("Overwriting a home the player already has is not a new home")
    void overwritingIsNotANewHome() {
        SlowCountingHomeStorage storage = new SlowCountingHomeStorage(1);
        HomeService homes = new HomeService(storage, HomeLimitPolicy.defaultPolicy());

        for (int i = 0; i < ALLOWANCE; i++) {
            assertThat(set(homes, "home" + i)).isInstanceOf(HomeService.SetHomeResult.Success.class);
        }
        assertThat(set(homes, "home0"))
                .describedAs("moving a home you already have is not asking for another one")
                .isInstanceOf(HomeService.SetHomeResult.Success.class);

        assertThat(storage.size()).isEqualTo(ALLOWANCE);
    }

    @Test
    @DisplayName("One at a time still gets exactly the allowance")
    void oneAtATimeGetsTheAllowance() {
        SlowCountingHomeStorage storage = new SlowCountingHomeStorage(1);
        HomeService homes = new HomeService(storage, HomeLimitPolicy.defaultPolicy());

        for (int i = 0; i < 10; i++) {
            var ignored = set(homes, "home" + i);
        }

        assertThat(storage.size()).isEqualTo(ALLOWANCE);
    }
}
