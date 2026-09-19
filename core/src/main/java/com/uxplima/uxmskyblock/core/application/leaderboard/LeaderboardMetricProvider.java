package com.uxplima.uxmskyblock.core.application.leaderboard;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * Pluggable provider resolving competitive leaderboard metrics for islands (Section 2.42).
 */
public interface LeaderboardMetricProvider {

    /**
     * Unique identifier for this leaderboard metric (e.g. LEVEL, WORTH, BANK).
     */
    String metricId();

    /**
     * User-facing display title for this metric.
     */
    String displayName();

    /**
     * Resolves the current numeric value of this metric for an island.
     */
    long resolveValue(IslandId islandId);

    /**
     * Formats the raw numeric value into a human-readable display string.
     */
    String formatValue(long value);
}
