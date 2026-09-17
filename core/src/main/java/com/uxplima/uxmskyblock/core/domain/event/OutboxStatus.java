package com.uxplima.uxmskyblock.core.domain.event;

/**
 * State of a transactional outbox event in the operational database pipeline.
 */
public enum OutboxStatus {
    PENDING,
    CLAIMED,
    PROCESSED,
    DEAD_LETTER;

    public boolean isTerminal() {
        return this == PROCESSED || this == DEAD_LETTER;
    }
}
