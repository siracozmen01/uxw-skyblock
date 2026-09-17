package com.uxplima.uxmskyblock.core.application.backup;

import java.util.List;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.backup.BackupCatalogRecord;
import com.uxplima.uxmskyblock.core.domain.backup.BackupLifecycleState;
import com.uxplima.uxmskyblock.core.domain.backup.BackupSetId;

/**
 * Outbound application port for persistent SQL cataloging of backup lifecycle operations.
 */
public interface BackupCatalogPort {

    /**
     * Persists or updates the backup catalog record.
     */
    void save(BackupCatalogRecord record);

    /**
     * Finds a backup catalog record by ID.
     */
    Optional<BackupCatalogRecord> findById(BackupSetId id);

    /**
     * Finds backup catalog records matching the specified gameplay root.
     */
    List<BackupCatalogRecord> findByRoot(String rootTypeId, String rootKey);

    /**
     * Updates the mutable lifecycle state of an active backup operation.
     */
    void updateState(BackupSetId id, BackupLifecycleState state, String failureReason);
}
