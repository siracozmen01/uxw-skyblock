package com.uxplima.uxmskyblock.core.domain.storage;

/**
 * Thrown when an unverified provider is rejected under strict verification mode.
 */
public class StorageProviderUnverifiedException extends RuntimeException {

    public StorageProviderUnverifiedException(String message) {
        super(message);
    }
}
