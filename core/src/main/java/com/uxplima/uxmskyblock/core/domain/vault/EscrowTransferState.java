package com.uxplima.uxmskyblock.core.domain.vault;

/**
 * Five-step lifecycle of a dual-slot write-ahead escrow transfer.
 */
public enum EscrowTransferState {
    INTENT,
    APPLYING,
    APPLIED,
    COMMITTED,
    ABORTED,
    RECOVERY_REQUIRED
}
