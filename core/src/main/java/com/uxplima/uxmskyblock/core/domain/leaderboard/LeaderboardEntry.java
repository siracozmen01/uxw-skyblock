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
 * @param formattedScore display string, for a reader with no language of its own such as the API
 * @param named whether {@code islandName} is a name the island was given rather than one made up
 *     from its id. A made up name is in one language, so a player is shown their own.
 */
public record LeaderboardEntry(
        int rank, IslandId islandId, String islandName, long score, String formattedScore, boolean named) {

    /** An entry whose name the island was given. */
    public LeaderboardEntry(int rank, IslandId islandId, String islandName, long score, String formattedScore) {
        this(rank, islandId, islandName, score, formattedScore, true);
    }

    public LeaderboardEntry {
        Objects.requireNonNull(islandId, "islandId");
        Objects.requireNonNull(islandName, "islandName");
        Objects.requireNonNull(formattedScore, "formattedScore");
        if (rank <= 0) {
            throw new IllegalArgumentException("rank must be positive: " + rank);
        }
    }
}
