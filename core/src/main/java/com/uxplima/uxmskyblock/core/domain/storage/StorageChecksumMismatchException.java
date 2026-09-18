package com.uxplima.uxmskyblock.core.domain.storage;

/**
 * Thrown when an application-level SHA-256 checksum verification fails.
 */
public class StorageChecksumMismatchException extends RuntimeException {

    public StorageChecksumMismatchException(String message) {
        super(message);
    }
}
