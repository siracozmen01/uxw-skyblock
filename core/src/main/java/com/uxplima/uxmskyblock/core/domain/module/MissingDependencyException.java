package com.uxplima.uxmskyblock.core.domain.module;

/**
 * Thrown when a required module dependency is not found or has an incompatible version.
 */
public final class MissingDependencyException extends RuntimeException {
    public MissingDependencyException(String message) {
        super(message);
    }
}
