package com.uxplima.uxmskyblock.core.domain.reward;

/**
 * Delivery state of an individual {@link RewardGrantComponent}.
 */
public enum RewardComponentState {
    /**
     * Component has not yet been delivered.
     */
    PENDING,

    /**
     * Component has been durably committed to the target protocol.
     * Completed components are NEVER re-delivered on retry or crash recovery.
     */
    COMMITTED,

    /**
     * Delivery failed during execution and remains uncommitted.
     */
    FAILED
}
