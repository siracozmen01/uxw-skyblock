package com.uxplima.uxmskyblock.persistence.session;

/**
 * Unchecked exception thrown when an unexpected database infrastructure or I/O failure
 * occurs during player session persistence operations.
 */
public final class SessionPersistenceException extends RuntimeException {

    public SessionPersistenceException(String message) {
        super(message);
    }

    public SessionPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
