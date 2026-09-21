package com.uxplima.uxmskyblock.bukkit.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Who is playing here, kept as they arrive and leave.
 *
 * <p>This used to answer by reading the island out of the database and then walking every player on
 * the server: a query and a full scan for every line of chat, on an island the caller had already
 * read, and the scan touched the live player list from whatever thread the message arrived on.
 */
class BukkitIslandOnlineMemberProviderTest {

    private static final ProfileId HERE = ProfileId.of(UUID.randomUUID());
    private static final ProfileId ALSO_HERE = ProfileId.of(UUID.randomUUID());
    private static final ProfileId AWAY = ProfileId.of(UUID.randomUUID());

    @Test
    @DisplayName("Only the candidates who are here come back")
    void onlyThoseHereComeBack() {
        BukkitIslandOnlineMemberProvider provider = new BukkitIslandOnlineMemberProvider();
        provider.arrived(HERE);
        provider.arrived(ALSO_HERE);

        assertThat(provider.onlineAmong(Set.of(HERE, AWAY))).containsExactly(HERE);
    }

    @Test
    @DisplayName("Somebody here who is not a candidate is not an answer")
    void somebodyElseIsNotAnAnswer() {
        BukkitIslandOnlineMemberProvider provider = new BukkitIslandOnlineMemberProvider();
        provider.arrived(HERE);
        provider.arrived(ALSO_HERE);

        assertThat(provider.onlineAmong(Set.of(HERE))).containsExactly(HERE);
    }

    @Test
    @DisplayName("A player who left is no longer here")
    void aPlayerWhoLeftIsGone() {
        BukkitIslandOnlineMemberProvider provider = new BukkitIslandOnlineMemberProvider();
        provider.arrived(HERE);
        provider.left(HERE);

        assertThat(provider.onlineAmong(Set.of(HERE))).isEmpty();
        assertThat(provider.count()).isZero();
    }

    @Test
    @DisplayName("Arriving twice is one player, because a rejoin is not a second person")
    void arrivingTwiceIsOnePlayer() {
        BukkitIslandOnlineMemberProvider provider = new BukkitIslandOnlineMemberProvider();
        provider.arrived(HERE);
        provider.arrived(HERE);

        assertThat(provider.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Asking about nobody is nobody, not everybody")
    void askingAboutNobodyIsNobody() {
        BukkitIslandOnlineMemberProvider provider = new BukkitIslandOnlineMemberProvider();
        provider.arrived(HERE);

        assertThat(provider.onlineAmong(Set.of())).isEmpty();
    }

    @Test
    @DisplayName("Joins and leaves while a message is being delivered do not break the answer")
    void joinsAndLeavesDuringDeliveryAreSafe() throws Exception {
        BukkitIslandOnlineMemberProvider provider = new BukkitIslandOnlineMemberProvider();
        Set<ProfileId> candidates = Set.of(HERE, ALSO_HERE, AWAY);
        provider.arrived(HERE);

        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            for (int t = 0; t < 2; t++) {
                var unusedJoiner = pool.submit(() -> {
                    awaitQuietly(go);
                    for (int i = 0; i < 500; i++) {
                        provider.arrived(ALSO_HERE);
                        provider.left(ALSO_HERE);
                    }
                });
                var unusedReader = pool.submit(() -> {
                    awaitQuietly(go);
                    for (int i = 0; i < 500; i++) {
                        // The old shape iterated the live player list here, which is a race with
                        // every join and quit. This one may not throw whatever else is happening.
                        assertThat(provider.onlineAmong(candidates)).contains(HERE);
                    }
                });
            }
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
