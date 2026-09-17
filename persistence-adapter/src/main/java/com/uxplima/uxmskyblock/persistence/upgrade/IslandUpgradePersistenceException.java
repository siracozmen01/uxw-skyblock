package com.uxplima.uxmskyblock.persistence.upgrade;

/**
 * Unchecked exception thrown when persistent island upgrade storage operations fail.
 */
public final class IslandUpgradePersistenceException extends RuntimeException {

    public IslandUpgradePersistenceException(String message) {
        super(message);
    }

    public IslandUpgradePersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
