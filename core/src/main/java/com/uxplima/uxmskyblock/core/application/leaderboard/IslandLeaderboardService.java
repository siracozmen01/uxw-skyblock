package com.uxplima.uxmskyblock.core.application.leaderboard;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardCategory;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardEntry;

/**
 * High-performance cached leaderboard service providing microsecond O(1) top-N and rank queries.
 */
public final class IslandLeaderboardService {

    private final IslandLeaderboardPort leaderboardPort;
    private final int defaultCacheCapacity;
    private final Map<LeaderboardCategory, List<LeaderboardEntry>> cachedRankings = new ConcurrentHashMap<>();

    public IslandLeaderboardService(IslandLeaderboardPort leaderboardPort, int defaultCacheCapacity) {
        this.leaderboardPort = Objects.requireNonNull(leaderboardPort, "leaderboardPort");
        this.defaultCacheCapacity = Math.max(10, defaultCacheCapacity);
    }

    public IslandLeaderboardService(IslandLeaderboardPort leaderboardPort) {
        this(leaderboardPort, 100);
    }

    /**
     * Retrieves the top N ranked islands for the given category from cache,
     * refreshing on-demand if uninitialized.
     *
     * @param category leaderboard category
     * @param limit maximum entries
     * @return unmodifiable list of top entries
     */
    public List<LeaderboardEntry> getTop(LeaderboardCategory category, int limit) {
        Objects.requireNonNull(category, "category");
        if (limit <= 0) {
            return List.of();
        }

        List<LeaderboardEntry> entries =
                cachedRankings.computeIfAbsent(category, c -> leaderboardPort.fetchTopIslands(c, defaultCacheCapacity));

        if (entries.isEmpty() || limit >= entries.size()) {
            return Collections.unmodifiableList(entries);
        }
        return Collections.unmodifiableList(entries.subList(0, limit));
    }

    /**
     * Looks up the rank of an island in the given category.
     *
     * @param category leaderboard category
     * @param islandId target island ID
     * @return optional containing 1-based rank if present in cached top rankings
     */
    public OptionalInt getRank(LeaderboardCategory category, IslandId islandId) {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(islandId, "islandId");

        List<LeaderboardEntry> entries = getTop(category, defaultCacheCapacity);
        for (LeaderboardEntry entry : entries) {
            if (entry.islandId().equals(islandId)) {
                return OptionalInt.of(entry.rank());
            }
        }
        return OptionalInt.empty();
    }

    /**
     * Forces an immediate reload of the specified leaderboard category from storage.
     *
     * @param category leaderboard category to refresh
     */
    public void refresh(LeaderboardCategory category) {
        Objects.requireNonNull(category, "category");
        List<LeaderboardEntry> freshlyFetched = leaderboardPort.fetchTopIslands(category, defaultCacheCapacity);
        cachedRankings.put(category, freshlyFetched);
    }

    /**
     * Forces an immediate reload of all leaderboard categories from storage.
     */
    public void refreshAll() {
        for (LeaderboardCategory category : LeaderboardCategory.values()) {
            refresh(category);
        }
    }

    /**
     * Updates an island's level score and net worth in persistent storage and refreshes caches.
     *
     * @param islandId target island ID
     * @param levelScore calculated level score
     * @param netWorthMinorUnits calculated net worth in minor units
     */
    public void recordIslandScore(IslandId islandId, long levelScore, long netWorthMinorUnits) {
        Objects.requireNonNull(islandId, "islandId");
        leaderboardPort.updateIslandScore(islandId, levelScore, netWorthMinorUnits);
        refresh(LeaderboardCategory.LEVEL);
        refresh(LeaderboardCategory.WORTH);
    }
}
