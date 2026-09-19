package com.uxplima.uxmskyblock.core.application.snapshot;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.application.backup.BackupCatalogPort;
import com.uxplima.uxmskyblock.core.application.backup.BackupService;
import com.uxplima.uxmskyblock.core.application.storage.ObjectStoragePort;
import com.uxplima.uxmskyblock.core.domain.backup.BackupArtifact;
import com.uxplima.uxmskyblock.core.domain.backup.BackupManifest;
import com.uxplima.uxmskyblock.core.domain.backup.BackupSetId;
import com.uxplima.uxmskyblock.core.domain.backup.BackupType;
import com.uxplima.uxmskyblock.core.domain.dimension.DimensionId;
import com.uxplima.uxmskyblock.core.domain.gamemode.PrimaryGameplayRootRef;
import com.uxplima.uxmskyblock.core.domain.storage.StorageBucket;

/**
 * Safety-critical restore engine coordinating fail-closed backup restore pipelines (Section 2.29).
 */
public final class IslandRestoreService {

    private final BackupCatalogPort catalogPort;
    private final ObjectStoragePort objectStoragePort;
    private final RootRelationalSnapshotPort relationalSnapshotPort;
    private final WorldDimensionSnapshotPort worldDimensionSnapshotPort;

    public sealed interface RestoreOutcome {
        record Success(BackupSetId backupSetId, int artifactsRestored) implements RestoreOutcome {}

        record Failure(String reason) implements RestoreOutcome {}
    }

    public IslandRestoreService(
            BackupCatalogPort catalogPort,
            ObjectStoragePort objectStoragePort,
            RootRelationalSnapshotPort relationalSnapshotPort,
            WorldDimensionSnapshotPort worldDimensionSnapshotPort) {
        this.catalogPort = Objects.requireNonNull(catalogPort, "catalogPort must not be null");
        this.objectStoragePort = Objects.requireNonNull(objectStoragePort, "objectStoragePort must not be null");
        this.relationalSnapshotPort =
                Objects.requireNonNull(relationalSnapshotPort, "relationalSnapshotPort must not be null");
        this.worldDimensionSnapshotPort =
                Objects.requireNonNull(worldDimensionSnapshotPort, "worldDimensionSnapshotPort must not be null");
    }

    public RestoreOutcome executeRestore(
            BackupManifest manifest, StorageBucket bucket, String rootPrefix, boolean disasterRecoveryConfirmed) {

        Objects.requireNonNull(manifest, "manifest must not be null");
        Objects.requireNonNull(bucket, "bucket must not be null");
        Objects.requireNonNull(rootPrefix, "rootPrefix must not be null");

        // 1. Disaster recovery boundary guard
        if (manifest.backupType() == BackupType.DATABASE_DISASTER_BACKUP && !disasterRecoveryConfirmed) {
            return new RestoreOutcome.Failure(
                    "Database disaster backup restore requires explicit disaster recovery confirmation");
        }

        String normalizedPrefix =
                rootPrefix.endsWith("/") ? rootPrefix.substring(0, rootPrefix.length() - 1) : rootPrefix;

        // 2. Discoverability & availability check
        String markerKey = normalizedPrefix + "/" + BackupService.AVAILABILITY_MARKER_FILE_NAME;
        if (!objectStoragePort.exists(bucket, markerKey)) {
            return new RestoreOutcome.Failure("Backup set missing AVAILABLE.marker - restore candidate disqualified");
        }

        // 3. Artifact download and cryptographic SHA-256 verification
        int restoredCount = 0;
        PrimaryGameplayRootRef rootRef = new PrimaryGameplayRootRef(
                com.uxplima.uxmskyblock.core.domain.gamemode.GameModeInstanceId.fromString(
                        manifest.rootKey() != null
                                ? manifest.rootKey()
                                : manifest.backupSetId().toString()),
                manifest.rootKey() != null
                        ? manifest.rootKey()
                        : manifest.backupSetId().toString(),
                manifest.rootTypeId() != null ? manifest.rootTypeId() : "ISLAND",
                manifest.createdAt() != null ? manifest.createdAt() : java.time.Instant.now());

        for (Map.Entry<String, BackupArtifact> entry : manifest.artifacts().entrySet()) {
            String filename = entry.getKey();
            BackupArtifact expected = entry.getValue();
            String artifactKey = normalizedPrefix + "/" + filename;

            Optional<byte[]> optBytes = objectStoragePort.getObject(bucket, artifactKey);
            if (optBytes.isEmpty()) {
                return new RestoreOutcome.Failure("Missing required artifact from storage: " + filename);
            }

            byte[] data = optBytes.get();
            String computedSha256 = computeSha256(data);
            if (!computedSha256.equalsIgnoreCase(expected.sha256Checksum())) {
                return new RestoreOutcome.Failure(
                        "Cryptographic checksum mismatch for artifact " + filename + ". Aborting restore pipeline.");
            }

            // 4. Dispatch restoration based on artifact role
            if (filename.contains("relational") || filename.endsWith(".sql") || filename.endsWith(".json")) {
                relationalSnapshotPort.restoreRelationalSnapshot(rootRef, data);
                restoredCount++;
            } else if (filename.contains("world") || filename.endsWith(".dat") || filename.endsWith(".zst")) {
                DimensionId dimId = filename.contains("nether")
                        ? DimensionId.THE_NETHER
                        : (filename.contains("end") ? DimensionId.THE_END : DimensionId.OVERWORLD);
                worldDimensionSnapshotPort.restoreWorldDimension(rootRef, dimId, data);
                restoredCount++;
            }
        }

        return new RestoreOutcome.Success(manifest.backupSetId(), restoredCount);
    }

    public BackupCatalogPort catalogPort() {
        return catalogPort;
    }

    private static String computeSha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }
}
