package com.uxplima.uxmskyblock.core.domain.vault;

/**
 * Thrown when dual-slot recovery detects duplication hazard, slot drift, or tampering requiring manual quarantine.
 */
public final class VaultRecoveryRequiredException extends RuntimeException {

    public VaultRecoveryRequiredException(String message) {
        super(message);
    }

    public VaultRecoveryRequiredException(String message, Throwable cause) {
        super(message, cause);
    }
}
