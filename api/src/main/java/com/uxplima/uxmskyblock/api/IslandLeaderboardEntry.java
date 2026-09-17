package com.uxplima.uxmskyblock.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable public entry on an island leaderboard.
 */
public record IslandLeaderboardEntry(int rank, UUID islandId, String islandName, long score, String formattedScore) {

    public IslandLeaderboardEntry {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(islandName, "islandName must not be null");
        Objects.requireNonNull(formattedScore, "formattedScore must not be null");
        if (rank <= 0) {
            throw new IllegalArgumentException("rank must be positive: " + rank);
        }
    }
}
