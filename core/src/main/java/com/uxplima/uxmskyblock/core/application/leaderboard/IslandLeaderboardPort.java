package com.uxplima.uxmskyblock.core.application.leaderboard;

import java.util.List;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardCategory;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardEntry;

/**
 * Outbound application port for querying competitive island leaderboards.
 */
public interface IslandLeaderboardPort {

    /**
     * Fetches the top islands in the specified category from persistent storage.
     *
     * @param category leaderboard category
     * @param limit maximum entries to fetch
     * @return list of leaderboard entries ordered by rank ascending
     */
    List<LeaderboardEntry> fetchTopIslands(LeaderboardCategory category, int limit);

    /**
     * Updates an island's level score and net worth for leaderboard indexing.
     *
     * @param islandId target island ID
     * @param levelScore calculated level score
     * @param netWorthMinorUnits calculated net worth in minor units
     */
    void updateIslandScore(IslandId islandId, long levelScore, long netWorthMinorUnits);
}
