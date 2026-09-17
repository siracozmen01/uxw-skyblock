package com.uxplima.uxmskyblock.persistence.leaderboard;

/**
 * Unchecked exception thrown when leaderboard storage operations fail.
 */
public final class IslandLeaderboardPersistenceException extends RuntimeException {

    public IslandLeaderboardPersistenceException(String message) {
        super(message);
    }

    public IslandLeaderboardPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
