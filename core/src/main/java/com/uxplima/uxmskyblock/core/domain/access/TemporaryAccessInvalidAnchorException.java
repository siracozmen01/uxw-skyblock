package com.uxplima.uxmskyblock.core.domain.access;

/**
 * Thrown when anchor parameters for a termination policy are missing, malformed,
 * or violate ownership constraints (e.g. anchorPlayerUuid does not own granteeProfileId).
 */
public class TemporaryAccessInvalidAnchorException extends RuntimeException {

    public TemporaryAccessInvalidAnchorException(String message) {
        super(message);
    }
}
