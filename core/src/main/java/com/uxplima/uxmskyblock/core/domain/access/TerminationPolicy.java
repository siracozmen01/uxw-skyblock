package com.uxplima.uxmskyblock.core.domain.access;

/**
 * Termination policy governing when a temporary access grant expires.
 */
public enum TerminationPolicy {
    /**
     * Persists until explicitly revoked by an authorized profile.
     */
    UNTIL_REVOKED,

    /**
     * Bound to the grantee's live player session generation (session epoch and active lease).
     */
    UNTIL_SESSION_END,

    /**
     * Node-local convenience policy bound to current server node process boot generation.
     */
    NODE_PROCESS_RESTART,

    /**
     * Time-bounded expiration evaluated against wall-clock timestamp.
     */
    UNTIL_TIMESTAMP
}
