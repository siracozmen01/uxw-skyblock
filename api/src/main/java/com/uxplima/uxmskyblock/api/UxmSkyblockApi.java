package com.uxplima.uxmskyblock.api;

/**
 * Root developer API surface for UXPLIMA Skyblock.
 */
public interface UxmSkyblockApi {

    UxmSkyblockQuery query();

    UxmSkyblockActions actions();

    /** The leaderboards, where another plugin adds a metric of its own. */
    com.uxplima.uxmskyblock.api.leaderboard.LeaderboardMetrics leaderboards();

    static UxmSkyblockApi getInstance() {
        return UxmSkyblockApiProvider.get();
    }
}
