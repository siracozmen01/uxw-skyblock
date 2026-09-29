package com.uxplima.uxmskyblock.api.leaderboard;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.uxplima.uxmskyblock.api.NamespacedId;

/** The metrics the skyblock plugin ranks: its own, and any another plugin registered. */
public interface LeaderboardMetrics {

    /**
     * Adds a metric.
     *
     * @throws IllegalArgumentException if a metric with the same id is already registered
     */
    void register(LeaderboardMetricProvider provider);

    Optional<LeaderboardMetricProvider> provider(NamespacedId metricId);

    Set<NamespacedId> metrics();

    /**
     * The board for one metric, best first, at most {@code limit} long. Values that tie keep one
     * order every time, by root id. Empty for a metric nobody registered or whose owner could not
     * be read. Called off the main thread.
     */
    List<RankedReading> ranked(NamespacedId metricId, int limit);
}
