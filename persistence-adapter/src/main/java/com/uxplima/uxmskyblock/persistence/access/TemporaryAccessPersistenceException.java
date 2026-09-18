package com.uxplima.uxmskyblock.persistence.access;

/**
 * Runtime exception thrown when a temporary access persistence operation fails.
 */
public class TemporaryAccessPersistenceException extends RuntimeException {

    public TemporaryAccessPersistenceException(String message) {
        super(message);
    }

    public TemporaryAccessPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
