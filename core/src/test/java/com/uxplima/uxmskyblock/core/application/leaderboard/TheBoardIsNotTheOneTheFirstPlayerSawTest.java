package com.uxplima.uxmskyblock.core.application.leaderboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardCategory;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The leaderboard is not the one the first player to ask happened to see.
 *
 * <p>The rankings were read the first time anybody asked and nothing ever read them again:
 * {@code refresh} and {@code refreshAll} had no caller anywhere, so {@code /is top} and the web
 * endpoint answered with that first board for as long as the process ran. A player who climbed to
 * first never appeared on it.
 *
 * <p>Holding the board is right, because building one is a sort across every island and the two
 * callers are a command and an HTTP endpoint anybody may hammer. Holding it for ever is not.
 */
class TheBoardIsNotTheOneTheFirstPlayerSawTest {

    /** A clock the test moves, and a port that counts what it was asked to build. */
    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-09-22T12:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final class CountingPort implements IslandLeaderboardPort {
        final AtomicInteger builds = new AtomicInteger();
        final AtomicReference<String> leader = new AtomicReference<>("the first island");

        @Override
        public List<LeaderboardEntry> fetchTopIslands(LeaderboardCategory category, int limit) {
            builds.incrementAndGet();
            return List.of(new LeaderboardEntry(
                    1, IslandId.of(UUID.randomUUID()), java.util.Objects.requireNonNull(leader.get()), 100L, "100"));
        }

        @Override
        public void updateIslandScore(IslandId islandId, long levelScore, long netWorthMinorUnits) {
            // Recorded elsewhere; this test is about what is read back.
        }
    }

    @Test
    @DisplayName("A board asked for twice inside the window is built once")
    void twoAsksInsideTheWindowBuildOnce() {
        CountingPort port = new CountingPort();
        IslandLeaderboardService service =
                new IslandLeaderboardService(port, 10, Duration.ofSeconds(60), new MovableClock());

        service.getTop(LeaderboardCategory.LEVEL, 10);
        service.getTop(LeaderboardCategory.LEVEL, 10);

        assertThat(port.builds.get())
                .describedAs("sorts across every island for two asks")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("A player who climbed to first appears once the window has passed")
    void theBoardCatchesUp() {
        CountingPort port = new CountingPort();
        MovableClock clock = new MovableClock();
        IslandLeaderboardService service = new IslandLeaderboardService(port, 10, Duration.ofSeconds(60), clock);

        assertThat(service.getTop(LeaderboardCategory.LEVEL, 10).get(0).islandName())
                .isEqualTo("the first island");

        port.leader.set("the island that climbed");
        assertThat(service.getTop(LeaderboardCategory.LEVEL, 10).get(0).islandName())
                .describedAs("inside the window, the board is the one in hand")
                .isEqualTo("the first island");

        clock.advance(Duration.ofSeconds(61));

        assertThat(service.getTop(LeaderboardCategory.LEVEL, 10).get(0).islandName())
                .describedAs("a board held for ever is a board nobody can climb")
                .isEqualTo("the island that climbed");
    }

    @Test
    @DisplayName("A rank is read off the same board, so it catches up with it")
    void arankCatchesUpToo() {
        CountingPort port = new CountingPort();
        MovableClock clock = new MovableClock();
        IslandLeaderboardService service = new IslandLeaderboardService(port, 10, Duration.ofSeconds(60), clock);

        service.getTop(LeaderboardCategory.WORTH, 10);
        int buildsAfterFirst = port.builds.get();

        clock.advance(Duration.ofSeconds(61));
        service.getRank(LeaderboardCategory.WORTH, IslandId.of(UUID.randomUUID()));

        assertThat(port.builds.get())
                .describedAs("the rank read the board again rather than the stale one")
                .isGreaterThan(buildsAfterFirst);
    }

    @Test
    @DisplayName("A window of nothing builds the board every time, which is what writing zero asks for")
    void aWindowOfNothingAlwaysBuilds() {
        CountingPort port = new CountingPort();
        IslandLeaderboardService service = new IslandLeaderboardService(port, 10, Duration.ZERO, new MovableClock());

        for (int ask = 0; ask < 5; ask++) {
            service.getTop(LeaderboardCategory.LEVEL, 10);
        }

        assertThat(port.builds.get()).isEqualTo(5);
    }

    @Test
    @DisplayName("Recording a score puts the new board in hand at once")
    void recordingAScoreRefreshesAtOnce() {
        CountingPort port = new CountingPort();
        IslandLeaderboardService service =
                new IslandLeaderboardService(port, 10, Duration.ofHours(1), new MovableClock());

        service.getTop(LeaderboardCategory.LEVEL, 10);
        port.leader.set("the island that climbed");

        service.recordIslandScore(IslandId.of(UUID.randomUUID()), 500L, 5_000L);

        assertThat(service.getTop(LeaderboardCategory.LEVEL, 10).get(0).islandName())
                .describedAs("the island whose score was just written")
                .isEqualTo("the island that climbed");
    }
}
