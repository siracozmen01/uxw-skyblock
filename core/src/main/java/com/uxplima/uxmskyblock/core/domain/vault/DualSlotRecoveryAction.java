package com.uxplima.uxmskyblock.core.domain.vault;

/**
 * Recovery action determined from inspecting the physical state of both source and destination slots.
 */
public enum DualSlotRecoveryAction {
    ABORT,
    CONDITIONAL_ROLLBACK,
    RECOVERY_REQUIRED
}
