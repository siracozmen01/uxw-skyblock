package com.uxplima.uxmskyblock.core.domain.profile;

/**
 * State machine phases for a profile switch write-ahead operation.
 */
public enum ProfileSwitchState {
    PREPARING,
    SOURCE_SNAPSHOTTED,
    TARGET_LOADED,
    TARGET_APPLY_INTENT,
    PLAYER_APPLIED,
    COMMITTED,
    FAILED,
    RECOVERY_REQUIRED
}
