package com.uxplima.uxmskyblock.api.leaderboard;

import java.util.Objects;

/** A reading with its place on the board and its value written the owner's way. */
public record RankedReading(int rank, MetricReading reading, String formatted) {

    public RankedReading {
        Objects.requireNonNull(reading, "reading must not be null");
        Objects.requireNonNull(formatted, "formatted must not be null");
        if (rank < 1) {
            throw new IllegalArgumentException("rank must be at least 1: " + rank);
        }
    }
}
