package com.uxplima.uxmskyblock.core.domain.storage;

/**
 * Thrown when an operation requires a storage capability not supported by the provider.
 */
public class StorageCapabilityMissingException extends RuntimeException {

    public StorageCapabilityMissingException(String message) {
        super(message);
    }
}
