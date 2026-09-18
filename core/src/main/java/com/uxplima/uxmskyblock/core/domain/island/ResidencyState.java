package com.uxplima.uxmskyblock.core.domain.island;

/**
 * Memory residency state of an island aggregate.
 */
public enum ResidencyState {
    /**
     * Active in L1 JVM heap / server node memory.
     */
    LOADED,

    /**
     * Cold in persistent storage without active heap residency.
     */
    UNLOADED
}
