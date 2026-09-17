package com.uxplima.uxmskyblock.core.domain.backup;

/**
 * Mutable lifecycle states tracked exclusively within the SQL operational catalog.
 */
public enum BackupLifecycleState {
    PLANNED,
    CAPTURING,
    STAGED,
    UPLOADING,
    VERIFYING,
    AVAILABLE,
    PARTIAL,
    FAILED,
    RECOVERY_REQUIRED,
    DELETING,
    DELETED;

    public boolean isTerminal() {
        return this == AVAILABLE || this == FAILED || this == DELETED;
    }

    public boolean isEligibleForRestore() {
        return this == AVAILABLE;
    }
}
