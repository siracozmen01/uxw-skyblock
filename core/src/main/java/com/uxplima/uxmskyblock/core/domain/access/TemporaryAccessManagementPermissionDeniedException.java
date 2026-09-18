package com.uxplima.uxmskyblock.core.domain.access;

/**
 * Thrown when a caller attempts to delegate ownership, management, bank administration,
 * or membership rights into a temporary access grant.
 */
public class TemporaryAccessManagementPermissionDeniedException extends RuntimeException {

    public TemporaryAccessManagementPermissionDeniedException(String message) {
        super(message);
    }
}
