package com.uxplima.uxmskyblock.persistence.reward;

/**
 * Runtime exception thrown when a reward storage persistence operation encounters a database error.
 */
public class RewardPersistenceException extends RuntimeException {

    public RewardPersistenceException(String message) {
        super(message);
    }

    public RewardPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
