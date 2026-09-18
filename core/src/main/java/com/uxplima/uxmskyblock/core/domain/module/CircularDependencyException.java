package com.uxplima.uxmskyblock.core.domain.module;

/**
 * Thrown when circular dependencies are detected in the module dependency graph.
 */
public final class CircularDependencyException extends RuntimeException {
    public CircularDependencyException(String message) {
        super(message);
    }
}
