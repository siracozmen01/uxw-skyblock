package com.uxplima.uxmskyblock.core.application.backup;

import com.uxplima.uxmskyblock.core.domain.backup.DatabaseBackupDialect;

/**
 * Outbound application port for catastrophic whole-database disaster recovery.
 *
 * <p>Enforces dialect-correct consistency mechanisms, credential redaction, and strict
 * disaster-recovery scoping separate from individual gameplay root rollbacks.
 */
public interface DatabaseBackupPort {

    /**
     * Captures a full database export artifact using dialect-appropriate consistency mechanisms.
     *
     * @param dialect the target database dialect
     * @return binary backup archive payload
     */
    byte[] captureDatabaseBackup(DatabaseBackupDialect dialect);

    /**
     * Restores the whole relational database from a disaster backup archive.
     *
     * @param backupArtifact binary backup archive payload
     * @param dialect the target database dialect
     * @param disasterRecoveryConfirmed administrative confirmation flag
     * @throws IllegalArgumentException if confirmation flag is false
     */
    void restoreDatabaseBackup(byte[] backupArtifact, DatabaseBackupDialect dialect, boolean disasterRecoveryConfirmed);
}
