package com.uxplima.uxmskyblock.persistence.event;

/**
 * Thrown when outbox or consumer inbox persistence operations fail.
 */
public final class OutboxPersistenceException extends RuntimeException {

    public OutboxPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
