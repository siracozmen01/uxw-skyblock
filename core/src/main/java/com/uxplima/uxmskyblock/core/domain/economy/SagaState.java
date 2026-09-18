package com.uxplima.uxmskyblock.core.domain.economy;

/**
 * Lifecycle state machine of an economy saga transaction.
 */
public enum SagaState {
    STARTED,
    COMMITTED,
    COMPENSATING,
    ROLLED_BACK,
    FAILED
}
