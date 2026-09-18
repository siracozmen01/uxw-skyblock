package com.uxplima.uxmskyblock.core.domain.vault;

/**
 * Lifecycle state of an exclusive pessimistic vault edit session.
 */
public enum VaultSessionState {
    ACTIVE,
    COMMITTED,
    ABORTED,
    RECOVERY_REQUIRED
}
