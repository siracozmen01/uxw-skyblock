package com.uxplima.uxmskyblock.api.leaderboard;

import java.util.List;

import com.uxplima.uxmskyblock.api.NamespacedId;

/**
 * A metric a plugin owns and the skyblock plugin ranks.
 *
 * <p>The board never holds the value. {@link #read} is the owner's own answer, asked each time the
 * board is built, and whatever the board keeps between builds is a copy of it. A plugin that adds a
 * metric keeps its value where it always did.
 */
public interface LeaderboardMetricProvider {

    /** The metric's id, such as {@code season:score}. The namespace is the owner's own. */
    NamespacedId metricId();

    /** The name the board shows, when the reader's language file names the metric nothing else. */
    String displayName();

    /** Who holds the canonical value: the context or plugin a disputed value is checked against. */
    String owner();

    /** The kind of gameplay root ranked, such as {@code ISLAND}. */
    String rootType();

    SortDirection sortDirection();

    MetricConsistency consistency();

    /**
     * The owner's reading of the roots nearest the top, at least {@code limit} of them when there
     * are that many. Their order does not matter: the board sorts them. Called off the main thread.
     */
    List<MetricReading> read(int limit);

    /** The value as a player reads it. */
    default String format(long value) {
        return Long.toString(value);
    }
}
