package com.uxplima.uxmskyblock.core.domain.island;

/**
 * Orthogonal administrative enforcement state of an island aggregate.
 */
public enum AdministrativeState {
    /**
     * Normal gameplay operations permitted.
     */
    NORMAL,

    /**
     * Administratively frozen under staff quarantine or anti-exploit lockdown.
     */
    FROZEN;

    /**
     * Checks whether administrative quarantine is active.
     *
     * @return true if FROZEN
     */
    public boolean isFrozen() {
        return this == FROZEN;
    }
}
