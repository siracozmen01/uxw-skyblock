package com.uxplima.uxmskyblock.core.domain.vault;

/**
 * Thrown when an edit session attempt to commit or mutate a vault page fails fencing or OCC version checks.
 */
public final class StaleVaultSessionException extends RuntimeException {

    public StaleVaultSessionException(String message) {
        super(message);
    }

    public StaleVaultSessionException(String message, Throwable cause) {
        super(message, cause);
    }
}
