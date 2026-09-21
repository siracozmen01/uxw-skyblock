package com.uxplima.uxmskyblock.core.application.alliance;

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

import com.uxplima.uxmskyblock.core.domain.alliance.AllianceInviteId;
import com.uxplima.uxmskyblock.core.domain.alliance.IslandAlliance;
import com.uxplima.uxmskyblock.core.domain.alliance.IslandAllianceInvite;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An island never ends up with more allies than the server allows.
 *
 * <p>Both islands are counted against the cap and then an alliance is written. Two acceptances at
 * once both read one below the cap, both pass, and both write, and an island that may have two
 * allies has three. Every one of them is shielded from friendly fire and may walk through the
 * island's lock, so an extra one is not a cosmetic miscount.
 */
class TheAllyCapHoldsUnderTwoAcceptancesTest {

    private static final int MAX_ALLIES = 2;
    private static final IslandId HOME = IslandId.of(UUID.randomUUID());
    private static final ProfileId SENDER = ProfileId.of(UUID.randomUUID());

    /** A storage whose count is slow, which is what a database is. */
    private static final class SlowCountingAllianceStorage implements IslandAllianceStoragePort {
        private final List<IslandAlliance> alliances = java.util.Collections.synchronizedList(new ArrayList<>());
        private final List<IslandAllianceInvite> invites = java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public void saveAlliance(IslandAlliance alliance) {
            alliances.add(alliance);
        }

        @Override
        public void removeAlliance(IslandId islandA, IslandId islandB) {}

        @Override
        public boolean areAllied(IslandId islandA, IslandId islandB) {
            return false;
        }

        @Override
        public List<IslandAlliance> findAlliances(IslandId islandId) {
            return List.copyOf(alliances);
        }

        @Override
        public int countAlliances(IslandId islandId) {
            int count = 0;
            synchronized (alliances) {
                for (IslandAlliance alliance : alliances) {
                    if (alliance.islandA().equals(islandId)
                            || alliance.islandB().equals(islandId)) {
                        count++;
                    }
                }
            }
            for (int spin = 0; spin < 2000; spin++) {
                Thread.onSpinWait();
            }
            return count;
        }

        @Override
        public void saveInvite(IslandAllianceInvite invite) {
            invites.add(invite);
        }

        @Override
        public Optional<IslandAllianceInvite> findInvite(IslandId senderIslandId, IslandId targetIslandId) {
            synchronized (invites) {
                return invites.stream()
                        .filter(i -> i.senderIslandId().equals(senderIslandId)
                                && i.targetIslandId().equals(targetIslandId))
                        .findFirst();
            }
        }

        @Override
        public List<IslandAllianceInvite> findPendingInvites(IslandId targetIslandId, Instant now) {
            return List.copyOf(invites);
        }

        @Override
        public void deleteInvite(IslandId senderIslandId, IslandId targetIslandId) {
            invites.removeIf(i -> i.senderIslandId().equals(senderIslandId)
                    && i.targetIslandId().equals(targetIslandId));
        }

        @Override
        public void purgeExpiredInvites(Instant now) {}

        int countFor(IslandId islandId) {
            int count = 0;
            synchronized (alliances) {
                for (IslandAlliance alliance : alliances) {
                    if (alliance.islandA().equals(islandId)
                            || alliance.islandB().equals(islandId)) {
                        count++;
                    }
                }
            }
            return count;
        }
    }

    @Test
    @DisplayName("Eight islands accepting at once leave the home island with the two allies it may have")
    void eightAcceptancesLeaveTheAllowedTwo() throws Exception {
        SlowCountingAllianceStorage storage = new SlowCountingAllianceStorage();
        IslandAllianceService alliances = new IslandAllianceService(storage);
        int threads = 8;
        List<IslandId> others = new ArrayList<>();
        Instant now = Instant.now();
        for (int t = 0; t < threads; t++) {
            IslandId other = IslandId.of(UUID.randomUUID());
            others.add(other);
            storage.saveInvite(new IslandAllianceInvite(
                    AllianceInviteId.random(), HOME, other, SENDER, now, now.plusSeconds(300)));
        }

        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (IslandId other : others) {
                var unused = pool.submit(() -> {
                    try {
                        go.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    try {
                        alliances.acceptInvite(HOME, other);
                    } catch (RuntimeException refused) {
                        // The cap said no, which is the whole point.
                    }
                });
            }
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(storage.countFor(HOME))
                .describedAs("an ally over the cap is shielded from friendly fire and walks through the lock")
                .isEqualTo(MAX_ALLIES);
    }

    @Test
    @DisplayName("One at a time still gets exactly the allies it may have")
    void oneAtATimeGetsTheAllowed() {
        SlowCountingAllianceStorage storage = new SlowCountingAllianceStorage();
        IslandAllianceService alliances = new IslandAllianceService(storage);
        Instant now = Instant.now();

        for (int i = 0; i < 5; i++) {
            IslandId other = IslandId.of(UUID.randomUUID());
            storage.saveInvite(new IslandAllianceInvite(
                    AllianceInviteId.random(), HOME, other, SENDER, now, now.plusSeconds(300)));
            try {
                alliances.acceptInvite(HOME, other);
            } catch (RuntimeException refused) {
                // expected once the cap is reached
            }
        }

        assertThat(storage.countFor(HOME)).isEqualTo(MAX_ALLIES);
    }
}
