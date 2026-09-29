package com.uxplima.uxmskyblock.core.application.leaderboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.uxplima.uxmskyblock.api.NamespacedId;
import com.uxplima.uxmskyblock.api.leaderboard.LeaderboardMetricProvider;
import com.uxplima.uxmskyblock.api.leaderboard.MetricConsistency;
import com.uxplima.uxmskyblock.api.leaderboard.MetricReading;
import com.uxplima.uxmskyblock.api.leaderboard.RankedReading;
import com.uxplima.uxmskyblock.api.leaderboard.SortDirection;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardCategory;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Any metric can be ranked, by whoever owns it, on whatever root it scores.
 *
 * <p>The game mode architecture names this test: dynamic metric providers register, sort, and expose
 * authoritative read contracts across generic gameplay roots. The board ranks; it does not own. Each
 * build reads the owner again, and nothing the board does writes a value back.
 */
class LeaderboardMetricProviderContractTest {

    private static final NamespacedId SEASON = NamespacedId.of("season:score");
    private static final NamespacedId SPEEDRUN = NamespacedId.of("factory:fastest_build");

    private final IslandId first = IslandId.of(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private final IslandId second = IslandId.of(UUID.fromString("00000000-0000-0000-0000-000000000002"));
    private final RecordingIslands islands = new RecordingIslands();
    private final LeaderboardMetricRegistry registry = new LeaderboardMetricRegistry();

    @Test
    @DisplayName("The shipped island metrics say who owns them, how fresh they are and what they rank")
    void theShippedMetricsDeclareTheirContract() {
        IslandMetricProviders.registerInto(registry, islands);

        assertThat(registry.metrics())
                .containsExactlyInAnyOrder(
                        IslandMetricProviders.LEVEL, IslandMetricProviders.WORTH, IslandMetricProviders.BANK_BALANCE);
        LeaderboardMetricProvider level =
                registry.provider(IslandMetricProviders.LEVEL).orElseThrow();
        LeaderboardMetricProvider bank =
                registry.provider(IslandMetricProviders.BANK_BALANCE).orElseThrow();
        assertThat(level.rootType()).isEqualTo(IslandMetricProviders.ISLAND);
        assertThat(level.sortDirection()).isEqualTo(SortDirection.HIGHEST_FIRST);
        assertThat(level.consistency()).isEqualTo(MetricConsistency.PERIODIC_ASYNC_SCAN);
        assertThat(bank.consistency()).isEqualTo(MetricConsistency.EVENT_DRIVEN_EXACT);
        assertThat(level.owner()).isNotEqualTo(bank.owner());
        assertThat(bank.format(123_456)).isEqualTo("1,234.56");
        assertThat(level.format(42)).isEqualTo("42");
    }

    @Test
    @DisplayName("A shipped board reads the island scores and writes none of them")
    void aShippedBoardReadsAndNeverWrites() {
        IslandMetricProviders.registerInto(registry, islands);
        islands.board = List.of(entry(1, second, 900), entry(2, first, 400));

        List<RankedReading> board = registry.ranked(IslandMetricProviders.WORTH, 10);

        assertThat(board)
                .extracting(ranked -> ranked.reading().rootId())
                .containsExactly(second.value().toString(), first.value().toString());
        assertThat(board.get(0).formatted()).isEqualTo("9.00");
        assertThat(islands.askedFor).containsExactly(LeaderboardCategory.WORTH);
        assertThat(islands.writes).isZero();
    }

    @Test
    @DisplayName("A metric on another root type ranks beside the island ones, lowest first when it says so")
    void anotherRootTypeRanks() {
        IslandMetricProviders.registerInto(registry, islands);
        registry.register(new Owned(
                SPEEDRUN,
                "factory:plot",
                SortDirection.LOWEST_FIRST,
                List.of(
                        new MetricReading("plot-c", "C", 95),
                        new MetricReading("plot-a", "A", 61),
                        new MetricReading("plot-b", "B", 78))));

        assertThat(registry.ranked(SPEEDRUN, 10))
                .extracting(RankedReading::rank, ranked -> ranked.reading().rootId())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, "plot-a"),
                        org.assertj.core.groups.Tuple.tuple(2, "plot-b"),
                        org.assertj.core.groups.Tuple.tuple(3, "plot-c"));
        assertThat(registry.provider(SPEEDRUN).orElseThrow().rootType()).isEqualTo("factory:plot");
    }

    @Test
    @DisplayName("Values that tie keep one order every time, and the board stops at its limit")
    void tiesAreStableAndTheLimitHolds() {
        registry.register(new Owned(
                SEASON,
                IslandMetricProviders.ISLAND,
                SortDirection.HIGHEST_FIRST,
                List.of(
                        new MetricReading("b", "B", 10), new MetricReading("c", "C", 30),
                        new MetricReading("a", "A", 10), new MetricReading("d", "D", 5))));

        List<RankedReading> board = registry.ranked(SEASON, 3);

        assertThat(board).extracting(ranked -> ranked.reading().rootId()).containsExactly("c", "a", "b");
        assertThat(registry.ranked(SEASON, 3)).isEqualTo(board);
        assertThat(registry.ranked(SEASON, 0)).isEmpty();
    }

    @Test
    @DisplayName("Each board is read from its owner again, so a change there shows at once")
    void theOwnerIsTheSourceOfTruth() {
        List<MetricReading> held = new ArrayList<>(List.of(new MetricReading("a", "A", 1)));
        Owned season = new Owned(SEASON, IslandMetricProviders.ISLAND, SortDirection.HIGHEST_FIRST, held);
        registry.register(season);

        registry.ranked(SEASON, 5);
        held.add(new MetricReading("b", "B", 2));

        assertThat(registry.ranked(SEASON, 5))
                .extracting(ranked -> ranked.reading().rootId())
                .containsExactly("b", "a");
        assertThat(season.reads.get()).isEqualTo(2);
        assertThat(season.lastLimit).isEqualTo(5);
    }

    @Test
    @DisplayName("A metric id is taken once, and has to be a lowercase namespace and key")
    void registrationIsGoverned() {
        IslandMetricProviders.registerInto(registry, islands);
        registry.register(new Owned(SEASON, IslandMetricProviders.ISLAND, SortDirection.HIGHEST_FIRST, List.of()));

        assertThatThrownBy(() -> registry.register(
                        new Owned(SEASON, IslandMetricProviders.ISLAND, SortDirection.LOWEST_FIRST, List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already registered");
        assertThatThrownBy(() -> registry.register(new Owned(
                        IslandMetricProviders.LEVEL,
                        IslandMetricProviders.ISLAND,
                        SortDirection.LOWEST_FIRST,
                        List.of())))
                .isInstanceOf(IllegalArgumentException.class);
        for (String bad : List.of("score", ":score", "season:", "Season:Score")) {
            assertThatThrownBy(() -> registry.register(new Owned(
                            NamespacedId.of(bad),
                            IslandMetricProviders.ISLAND,
                            SortDirection.HIGHEST_FIRST,
                            List.of())))
                    .describedAs(bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("An owner that cannot be read leaves its own board empty and no other")
    void aFailingOwnerIsContained() {
        IslandMetricProviders.registerInto(registry, islands);
        islands.board = List.of(entry(1, first, 7));
        registry.register(new Owned(SEASON, IslandMetricProviders.ISLAND, SortDirection.HIGHEST_FIRST, List.of()) {
            @Override
            public List<MetricReading> read(int limit) {
                throw new IllegalStateException("season database away");
            }
        });

        assertThat(registry.ranked(SEASON, 10)).isEmpty();
        assertThat(registry.ranked(IslandMetricProviders.LEVEL, 10)).hasSize(1);
        assertThat(registry.ranked(NamespacedId.of("nobody:registered"), 10)).isEmpty();
    }

    private static LeaderboardEntry entry(int rank, IslandId islandId, long score) {
        return new LeaderboardEntry(rank, islandId, "Island " + rank, score, Long.toString(score));
    }

    /** A metric another plugin owns, holding its values where it always did. */
    private static class Owned implements LeaderboardMetricProvider {

        private final NamespacedId id;
        private final String rootType;
        private final SortDirection direction;
        private final List<MetricReading> held;
        final AtomicInteger reads = new AtomicInteger();
        int lastLimit;

        Owned(NamespacedId id, String rootType, SortDirection direction, List<MetricReading> held) {
            this.id = id;
            this.rootType = rootType;
            this.direction = direction;
            this.held = held;
        }

        @Override
        public NamespacedId metricId() {
            return id;
        }

        @Override
        public String displayName() {
            return id.asString();
        }

        @Override
        public String owner() {
            return "a plugin of its own";
        }

        @Override
        public String rootType() {
            return rootType;
        }

        @Override
        public SortDirection sortDirection() {
            return direction;
        }

        @Override
        public MetricConsistency consistency() {
            return MetricConsistency.BATCH_SCHEDULED;
        }

        @Override
        public List<MetricReading> read(int limit) {
            reads.incrementAndGet();
            lastLimit = limit;
            return List.copyOf(held);
        }
    }

    /** The island scores the level scan and the bank keep, recording what the board asks of them. */
    private static final class RecordingIslands implements IslandLeaderboardPort {

        private List<LeaderboardEntry> board = List.of();
        private final List<LeaderboardCategory> askedFor = new ArrayList<>();
        private int writes;

        @Override
        public List<LeaderboardEntry> fetchTopIslands(LeaderboardCategory category, int limit) {
            askedFor.add(category);
            return board;
        }

        @Override
        public void updateIslandScore(IslandId islandId, long levelScore, long netWorthMinorUnits) {
            writes++;
        }
    }
}
