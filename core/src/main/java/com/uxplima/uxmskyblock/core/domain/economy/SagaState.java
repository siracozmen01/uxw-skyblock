package com.uxplima.uxmskyblock.core.domain.economy;

/**
 * Lifecycle state machine of an economy saga transaction.
 */
public enum SagaState {
    STARTED,
    COMMITTED,
    COMPENSATING,
    /**
     * A deposit is about to give the player's wallet its money back. It is written before the wallet
     * is asked, because a wallet cannot say whether it was already paid: a saga found here after a
     * crash may have refunded or may not, and paying it again would pay twice.
     */
    REFUNDING_WALLET,
    ROLLED_BACK,
    FAILED
}
