package com.uxplima.uxmskyblock.core.domain.module;

/**
 * Thrown when multiple providers register for the same capability without an explicit
 * selected-provider declared in configuration.
 */
public final class CapabilityCollisionException extends RuntimeException {
    public CapabilityCollisionException(String message) {
        super(message);
    }
}
