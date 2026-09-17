package com.uxplima.uxmskyblock.core.domain.backup;

/**
 * Categorical scope for a backup operation.
 */
public enum BackupType {
    /** Targets a single stable primary gameplay root (e.g. Island, Vessel). */
    ROOT_BACKUP,

    /** Whole-database catastrophic disaster recovery backup. */
    DATABASE_DISASTER_BACKUP
}
