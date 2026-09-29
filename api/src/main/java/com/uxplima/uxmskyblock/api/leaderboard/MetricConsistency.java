package com.uxplima.uxmskyblock.api.leaderboard;

/** How fresh a metric's reading is, so a board can say what it shows. */
public enum MetricConsistency {
    /** Written as each change happens: the reading is exact when it is read. */
    EVENT_DRIVEN_EXACT,
    /** Recomputed by a scan that runs in the background: the reading trails the world. */
    PERIODIC_ASYNC_SCAN,
    /** Recomputed on a schedule: the reading is as of the last run. */
    BATCH_SCHEDULED
}
