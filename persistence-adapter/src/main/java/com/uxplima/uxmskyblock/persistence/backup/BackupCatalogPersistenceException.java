package com.uxplima.uxmskyblock.persistence.backup;

import org.jspecify.annotations.Nullable;

/**
 * Thrown when backup catalog persistence operations fail due to underlying SQL errors.
 */
public final class BackupCatalogPersistenceException extends RuntimeException {

    public BackupCatalogPersistenceException(String message) {
        super(message);
    }

    public BackupCatalogPersistenceException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
