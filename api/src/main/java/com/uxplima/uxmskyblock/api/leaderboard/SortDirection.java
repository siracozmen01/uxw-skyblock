package com.uxplima.uxmskyblock.api.leaderboard;

/** Which end of a metric is the top of its board. */
public enum SortDirection {
    /** The largest value ranks first: a level, a balance, a count. */
    HIGHEST_FIRST,
    /** The smallest value ranks first: a time, a number of attempts. */
    LOWEST_FIRST
}
