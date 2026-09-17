package com.uxplima.uxmskyblock.core.domain.backup;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable disaster-recovery document published alongside artifacts as {@code manifest.json}.
 *
 * <p>Represents fixed, immutable facts about a completed backup generation.
 * Continuously mutable operational state fields (such as {@code status = UPLOADING})
 * are strictly forbidden in this document.
 */
public record BackupManifest(
        BackupSetId backupSetId,
        BackupType backupType,
        String rootTypeId,
        String rootKey,
        Instant createdAt,
        long authorityEpoch,
        long dbVersion,
        int schemaVersion,
        String pluginVersion,
        Map<String, BackupArtifact> artifacts,
        String consistencyResult) {

    public BackupManifest {
        Objects.requireNonNull(backupSetId, "backupSetId");
        Objects.requireNonNull(backupType, "backupType");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(pluginVersion, "pluginVersion");
        Objects.requireNonNull(consistencyResult, "consistencyResult");
        artifacts = (artifacts == null) ? Map.of() : Map.copyOf(artifacts);
    }

    /**
     * Looks up an artifact descriptor by relative filename.
     */
    public Optional<BackupArtifact> getArtifact(String filename) {
        return Optional.ofNullable(artifacts.get(filename));
    }

    /**
     * Validates whether an actual computed checksum matches the recorded manifest checksum.
     */
    public boolean matchesChecksum(String filename, String actualSha256) {
        BackupArtifact artifact = artifacts.get(filename);
        if (artifact == null || actualSha256 == null) {
            return false;
        }
        return artifact.sha256Checksum().equalsIgnoreCase(actualSha256.trim());
    }
}
