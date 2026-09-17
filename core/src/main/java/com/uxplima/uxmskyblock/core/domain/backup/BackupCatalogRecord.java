package com.uxplima.uxmskyblock.core.domain.backup;

import java.time.Instant;
import java.util.Objects;

/**
 * Mutable operational record stored in canonical SQL persistence to track backup lifecycle progression.
 */
public record BackupCatalogRecord(
        BackupSetId backupSetId,
        BackupType backupType,
        String targetRootTypeId,
        String targetRootKey,
        BackupLifecycleState state,
        long authorityEpoch,
        long dbVersion,
        int schemaVersion,
        String pluginVersion,
        String failureReason,
        Instant createdAt,
        Instant completedAt,
        Instant updatedAt) {

    public BackupCatalogRecord {
        Objects.requireNonNull(backupSetId, "backupSetId");
        Objects.requireNonNull(backupType, "backupType");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(pluginVersion, "pluginVersion");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public BackupCatalogRecord withState(BackupLifecycleState newState, String reason, Instant timestamp) {
        Instant completed = (newState == BackupLifecycleState.AVAILABLE
                        || newState == BackupLifecycleState.FAILED
                        || newState == BackupLifecycleState.DELETED)
                ? timestamp
                : this.completedAt;
        return new BackupCatalogRecord(
                backupSetId,
                backupType,
                targetRootTypeId,
                targetRootKey,
                newState,
                authorityEpoch,
                dbVersion,
                schemaVersion,
                pluginVersion,
                reason,
                createdAt,
                completed,
                timestamp);
    }
}
