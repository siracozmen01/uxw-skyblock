package com.uxplima.uxmskyblock.persistence.bank;

/**
 * Exception thrown when an island bank persistence operation encounters an unrecoverable error.
 */
public class IslandBankPersistenceException extends RuntimeException {

    public IslandBankPersistenceException(String message) {
        super(message);
    }

    public IslandBankPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
