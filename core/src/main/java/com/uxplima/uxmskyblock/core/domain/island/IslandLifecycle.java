package com.uxplima.uxmskyblock.core.domain.island;

/**
 * Lifecycle state of an island aggregate within the persistent grid.
 */
public enum IslandLifecycle {
    ACTIVE,
    DELETING,
    RECYCLING,
    ARCHIVED;

    /**
     * Checks whether the lifecycle is in an operational (active) state.
     *
     * @return true if ACTIVE, false if undergoing disposal or archival
     */
    public boolean isOperational() {
        return this == ACTIVE;
    }
}
