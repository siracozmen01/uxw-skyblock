package com.uxplima.uxmskyblock.persistence.backup;

/**
 * Thrown when backup catalog persistence operations fail due to underlying SQL errors.
 */
public final class BackupCatalogPersistenceException extends RuntimeException {

    public BackupCatalogPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
