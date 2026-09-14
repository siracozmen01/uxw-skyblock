package com.uxplima.uxmskyblock.core.domain.durability;

/**
 * Pure domain classification of mutation durability requirements.
 *
 * <p>Separates critical mutations requiring immediate durability from routine ambient mutations
 * eligible for periodic checkpointing.
 */
public enum DurabilityClassification {

    /**
     * Critical/value-sensitive mutations requiring immediate durable persistence through
     * their owning subsystem protocol before operation completion.
     */
    IMMEDIATE,

    /**
     * Routine mutable state eligible for periodic checkpointing under routine ambient cadence.
     */
    CHECKPOINTED
}
