package com.uxplima.uxmskyblock.persistence.social;

/**
 * Exception thrown when an island social persistence operation encounters an unrecoverable SQL error.
 */
public class SocialPersistenceException extends RuntimeException {

    public SocialPersistenceException(String message) {
        super(message);
    }

    public SocialPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
