package com.uxplima.uxmskyblock.persistence.inventory;

/**
 * Unchecked exception thrown when a physical database operation fails during inventory persistence.
 */
public class InventoryPersistenceException extends RuntimeException {

    public InventoryPersistenceException(String message) {
        super(message);
    }

    public InventoryPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
