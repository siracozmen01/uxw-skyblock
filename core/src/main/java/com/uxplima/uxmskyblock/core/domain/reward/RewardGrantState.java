package com.uxplima.uxmskyblock.core.domain.reward;

/**
 * State lifecycle for a parent {@link RewardGrant} aggregate.
 */
public enum RewardGrantState {
    /**
     * Grant has been issued and is awaiting claim by the recipient.
     */
    PENDING,

    /**
     * Claim is actively in flight across one or more delivery protocols.
     */
    CLAIMING,

    /**
     * All components have been successfully and durably delivered (COMMITTED).
     */
    CLAIMED,

    /**
     * Grant expired before being claimed.
     */
    EXPIRED,

    /**
     * Partial delivery failure occurred during claim; requires administrative or automated recovery.
     */
    RECOVERY_REQUIRED
}
