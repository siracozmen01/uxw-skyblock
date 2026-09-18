package com.uxplima.uxmskyblock.persistence.alliance;

/**
 * Exception thrown when an island alliance persistence operation encounters an unrecoverable SQL error.
 */
public class AlliancePersistenceException extends RuntimeException {

    public AlliancePersistenceException(String message) {
        super(message);
    }

    public AlliancePersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
