package com.uxplima.uxmskyblock.persistence.island;

/**
 * Exception thrown when an island storage or authority persistence operation fails.
 */
public class IslandPersistenceException extends RuntimeException {

    public IslandPersistenceException(String message) {
        super(message);
    }

    public IslandPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
