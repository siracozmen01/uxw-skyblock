package com.uxplima.uxmskyblock.persistence.season;

/**
 * Exception thrown when an island season persistence operation encounters an unrecoverable SQL error.
 */
public class SeasonPersistenceException extends RuntimeException {

    public SeasonPersistenceException(String message) {
        super(message);
    }

    public SeasonPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
