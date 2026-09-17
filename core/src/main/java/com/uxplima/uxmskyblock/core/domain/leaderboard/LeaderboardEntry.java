package com.uxplima.uxmskyblock.core.domain.leaderboard;

import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * Immutable domain record representing an island's standing on a leaderboard.
 *
 * @param rank 1-based rank position
 * @param islandId target island ID
 * @param islandName custom name or formatted identifier
 * @param score numeric score
 * @param formattedScore display string
 */
public record LeaderboardEntry(int rank, IslandId islandId, String islandName, long score, String formattedScore) {

    public LeaderboardEntry {
        Objects.requireNonNull(islandId, "islandId");
        Objects.requireNonNull(islandName, "islandName");
        Objects.requireNonNull(formattedScore, "formattedScore");
        if (rank <= 0) {
            throw new IllegalArgumentException("rank must be positive: " + rank);
        }
    }
}
