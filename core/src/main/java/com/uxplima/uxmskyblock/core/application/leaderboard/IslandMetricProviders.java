package com.uxplima.uxmskyblock.core.application.leaderboard;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.uxplima.uxmskyblock.api.NamespacedId;
import com.uxplima.uxmskyblock.api.leaderboard.LeaderboardMetricProvider;
import com.uxplima.uxmskyblock.api.leaderboard.MetricConsistency;
import com.uxplima.uxmskyblock.api.leaderboard.MetricReading;
import com.uxplima.uxmskyblock.api.leaderboard.SortDirection;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardCategory;

/**
 * The island metrics the plugin ships: level, worth and bank balance.
 *
 * <p>Each reads the island scores the level scan and the bank already keep. The level scan owns the
 * level and the worth, and the bank owns its balance.
 */
public final class IslandMetricProviders {

    public static final NamespacedId LEVEL = NamespacedId.of("uxm:level");
    public static final NamespacedId WORTH = NamespacedId.of("uxm:worth");
    public static final NamespacedId BANK_BALANCE = NamespacedId.of("uxm:bank_balance");

    /** The root type an island is. */
    public static final String ISLAND = "ISLAND";

    private IslandMetricProviders() {}

    /** Registers the three into a registry. */
    public static void registerInto(LeaderboardMetricRegistry registry, IslandLeaderboardPort islands) {
        Objects.requireNonNull(registry, "registry must not be null");
        Objects.requireNonNull(islands, "islands must not be null");
        registry.register(new Island(
                LEVEL,
                "Island level",
                "island level scan",
                LeaderboardCategory.LEVEL,
                MetricConsistency.PERIODIC_ASYNC_SCAN,
                false,
                islands));
        registry.register(new Island(
                WORTH,
                "Island worth",
                "island level scan",
                LeaderboardCategory.WORTH,
                MetricConsistency.PERIODIC_ASYNC_SCAN,
                true,
                islands));
        registry.register(new Island(
                BANK_BALANCE,
                "Island bank",
                "island bank",
                LeaderboardCategory.BANK,
                MetricConsistency.EVENT_DRIVEN_EXACT,
                true,
                islands));
    }

    /** The category a shipped island metric reads, for a caller that ranks it the older way. */
    public static java.util.Optional<LeaderboardCategory> categoryOf(NamespacedId metricId) {
        if (LEVEL.equals(metricId)) {
            return java.util.Optional.of(LeaderboardCategory.LEVEL);
        }
        if (WORTH.equals(metricId)) {
            return java.util.Optional.of(LeaderboardCategory.WORTH);
        }
        if (BANK_BALANCE.equals(metricId)) {
            return java.util.Optional.of(LeaderboardCategory.BANK);
        }
        return java.util.Optional.empty();
    }

    private record Island(
            NamespacedId metricId,
            String displayName,
            String owner,
            LeaderboardCategory category,
            MetricConsistency consistency,
            boolean money,
            IslandLeaderboardPort islands)
            implements LeaderboardMetricProvider {

        @Override
        public String rootType() {
            return ISLAND;
        }

        @Override
        public SortDirection sortDirection() {
            return SortDirection.HIGHEST_FIRST;
        }

        @Override
        public List<MetricReading> read(int limit) {
            return islands.fetchTopIslands(category, limit).stream()
                    .map(entry ->
                            new MetricReading(entry.islandId().value().toString(), entry.islandName(), entry.score()))
                    .toList();
        }

        @Override
        public String format(long value) {
            // Money is kept in cents.
            return money ? String.format(Locale.ROOT, "%,.2f", value / 100.0) : Long.toString(value);
        }
    }
}
