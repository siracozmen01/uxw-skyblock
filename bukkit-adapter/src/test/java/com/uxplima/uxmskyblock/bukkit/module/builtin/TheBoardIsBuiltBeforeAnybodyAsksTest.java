package com.uxplima.uxmskyblock.bukkit.module.builtin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.uxplima.uxmskyblock.bukkit.test.InlineSchedulerPort;
import com.uxplima.uxmskyblock.core.application.announce.IslandAnnouncer;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardPort;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardCategory;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The board is built before anybody asks, and the top is announced when it changes.
 *
 * <p>Nothing built a board except the player who asked for one, so the first ask after a restart
 * paid for a sort across every island while that player waited. And the leaderboards topic of the
 * webhook service, which an operator can give a URL, had nothing that ever filled it.
 */
class TheBoardIsBuiltBeforeAnybodyAsksTest {

    private static final IslandId FIRST = IslandId.of(UUID.randomUUID());
    private static final IslandId SECOND = IslandId.of(UUID.randomUUID());

    private static final class SwitchablePort implements IslandLeaderboardPort {
        final AtomicInteger builds = new AtomicInteger();
        final AtomicReference<IslandId> leader = new AtomicReference<>(FIRST);

        @Override
        public List<LeaderboardEntry> fetchTopIslands(LeaderboardCategory category, int limit) {
            builds.incrementAndGet();
            IslandId top = leader.get();
            return List.of(new LeaderboardEntry(1, top == null ? FIRST : top, "an island", 100L, "100"));
        }

        @Override
        public void updateIslandScore(IslandId islandId, long levelScore, long netWorthMinorUnits) {
            // Not what this test is about.
        }
    }

    private static final class Recorder implements IslandAnnouncer {
        final List<String> heard = new ArrayList<>();

        @Override
        public void notifyAlliance(String allianceName, String action, String actorName, String targetName) {
            heard.add("alliance");
        }

        @Override
        public void notifyAdminAudit(
                String eventType, String severity, String description, Map<String, String> details) {
            heard.add("audit");
        }

        @Override
        public void notifyLeaderboard(String metricName, List<LeaderboardEntry> topEntries) {
            heard.add("leaderboard:" + metricName);
        }
    }

    private static IslandLeaderboardService serviceOver(IslandLeaderboardPort port) {
        return new IslandLeaderboardService(port, 10, Duration.ofHours(1), java.time.Clock.systemUTC());
    }

    @Test
    @DisplayName("Every board is built without anybody asking for one")
    void everyBoardIsBuilt() {
        SwitchablePort port = new SwitchablePort();
        LeaderboardFeatureModule module =
                new LeaderboardFeatureModule(serviceOver(port), new InlineSchedulerPort(), Duration.ofMinutes(5), null);

        module.rebuildAndAnnounce();

        assertThat(port.builds.get())
                .describedAs("one sort per category, with no player waiting for any of them")
                .isEqualTo(LeaderboardCategory.values().length);
    }

    @Test
    @DisplayName("The top is announced once, and not again while it is the same island")
    void theTopIsAnnouncedWhenItChanges() {
        SwitchablePort port = new SwitchablePort();
        Recorder recorder = new Recorder();
        LeaderboardFeatureModule module = new LeaderboardFeatureModule(
                serviceOver(port), new InlineSchedulerPort(), Duration.ofMinutes(5), recorder);

        module.rebuildAndAnnounce();
        int afterFirst = recorder.heard.size();
        module.rebuildAndAnnounce();

        assertThat(afterFirst)
                .describedAs("one announcement per category the first time round")
                .isEqualTo(LeaderboardCategory.values().length);
        assertThat(recorder.heard)
                .describedAs("a board saying the same thing again is a channel nobody reads")
                .hasSize(afterFirst);
    }

    @Test
    @DisplayName("A new leader is announced")
    void anewLeaderIsAnnounced() {
        SwitchablePort port = new SwitchablePort();
        Recorder recorder = new Recorder();
        LeaderboardFeatureModule module = new LeaderboardFeatureModule(
                serviceOver(port), new InlineSchedulerPort(), Duration.ofMinutes(5), recorder);

        module.rebuildAndAnnounce();
        int afterFirst = recorder.heard.size();

        port.leader.set(SECOND);
        module.rebuildAndAnnounce();

        assertThat(recorder.heard.size())
                .describedAs("the island that took first place")
                .isGreaterThan(afterFirst);
    }

    @Test
    @DisplayName("A node with nowhere to announce to still builds its boards")
    void noAnnouncerStillBuilds() {
        SwitchablePort port = new SwitchablePort();
        LeaderboardFeatureModule module =
                new LeaderboardFeatureModule(serviceOver(port), new InlineSchedulerPort(), Duration.ofMinutes(5), null);

        module.rebuildAndAnnounce();

        assertThat(port.builds.get()).isGreaterThan(0);
    }

    @Test
    @DisplayName("A board that will not build does not stop the others")
    void oneFailureDoesNotStopTheRest() {
        AtomicInteger builds = new AtomicInteger();
        IslandLeaderboardPort angry = new IslandLeaderboardPort() {
            @Override
            public List<LeaderboardEntry> fetchTopIslands(LeaderboardCategory category, int limit) {
                builds.incrementAndGet();
                if (category == LeaderboardCategory.LEVEL) {
                    throw new IllegalStateException("the database is gone");
                }
                return List.of(new LeaderboardEntry(1, FIRST, "an island", 100L, "100"));
            }

            @Override
            public void updateIslandScore(IslandId islandId, long levelScore, long netWorthMinorUnits) {
                // Not what this test is about.
            }
        };

        LeaderboardFeatureModule module = new LeaderboardFeatureModule(
                serviceOver(angry), new InlineSchedulerPort(), Duration.ofMinutes(5), null);

        module.rebuildAndAnnounce();

        assertThat(builds.get())
                .describedAs("every category was tried, not just the ones before the failure")
                .isGreaterThanOrEqualTo(LeaderboardCategory.values().length);
    }
}
