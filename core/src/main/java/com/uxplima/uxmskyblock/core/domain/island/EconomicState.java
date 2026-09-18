package com.uxplima.uxmskyblock.core.domain.island;

/**
 * Orthogonal economic solvency state of an island aggregate.
 */
public enum EconomicState {
    /**
     * Solvent and operational.
     */
    NORMAL,

    /**
     * Financial arrears pending remediation grace period.
     */
    BANKRUPTCY_GRACE,

    /**
     * Delinquent account with frozen economic operations.
     */
    BANKRUPTCY_LOCKED;

    /**
     * Validates whether a transition from this state to the target state is allowed by domain rules.
     *
     * <p>Escalation invariant: cannot jump directly from {@code NORMAL} to {@code BANKRUPTCY_LOCKED};
     * must transit through {@code BANKRUPTCY_GRACE}. Instant remediation to {@code NORMAL} is always allowed.
     *
     * @param target the desired target state
     * @return true if valid, false if invalid transition
     */
    public boolean canTransitionTo(EconomicState target) {
        if (this == target) {
            return true;
        }
        if (this == NORMAL && target == BANKRUPTCY_LOCKED) {
            return false;
        }
        return true;
    }
}
