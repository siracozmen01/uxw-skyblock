package com.uxplima.uxmskyblock.core.domain.module;

/**
 * Thrown when a module declares an api-compatibility range incompatible with the running API.
 */
public final class IncompatibleApiException extends RuntimeException {
    public IncompatibleApiException(String message) {
        super(message);
    }
}
