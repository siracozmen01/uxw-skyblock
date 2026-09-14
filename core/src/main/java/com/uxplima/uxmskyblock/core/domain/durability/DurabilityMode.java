package com.uxplima.uxmskyblock.core.domain.durability;

/**
 * Pure domain enum representing canonical player-state durability modes.
 *
 * <p>In V1, the canonical product mode is {@link #HYBRID}.
 */
public enum DurabilityMode {

    /**
     * Hybrid durability: critical economic/value-sensitive mutations are immediately durable
     * under owning subsystem protocols, while routine ambient state is periodically checkpointed.
     */
    HYBRID
}
