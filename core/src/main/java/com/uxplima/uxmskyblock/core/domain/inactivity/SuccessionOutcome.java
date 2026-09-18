package com.uxplima.uxmskyblock.core.domain.inactivity;

/**
 * Result outcome classification after evaluating an island for leader inactivity and abandonment.
 */
public enum SuccessionOutcome {
    SKIPPED_POLICY_DISABLED,
    SKIPPED_ACTIVE_OWNER,
    SKIPPED_NO_ELIGIBLE_SUCCESSOR,
    SUCCESSION_EXECUTED,
    ABANDONED_ARCHIVED,
    ABANDONED_DELETED
}
