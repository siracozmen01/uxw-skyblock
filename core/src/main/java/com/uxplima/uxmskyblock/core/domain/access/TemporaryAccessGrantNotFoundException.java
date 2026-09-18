package com.uxplima.uxmskyblock.core.domain.access;

/**
 * Thrown when a requested temporary access grant is not found.
 */
public class TemporaryAccessGrantNotFoundException extends RuntimeException {

    public TemporaryAccessGrantNotFoundException(GrantId grantId) {
        super("Temporary access grant not found: " + grantId);
    }
}
