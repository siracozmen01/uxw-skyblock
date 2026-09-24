package com.uxplima.uxmskyblock.core.application.snapshot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.core.application.backup.BackupCatalogPort;
import com.uxplima.uxmskyblock.core.application.backup.BackupService;
import com.uxplima.uxmskyblock.core.application.freeze.IslandAdminFreezeService;
import com.uxplima.uxmskyblock.core.application.storage.ObjectStoragePort;
import com.uxplima.uxmskyblock.core.domain.backup.BackupArtifact;
import com.uxplima.uxmskyblock.core.domain.backup.BackupManifest;
import com.uxplima.uxmskyblock.core.domain.backup.BackupSetId;
import com.uxplima.uxmskyblock.core.domain.backup.BackupType;
import com.uxplima.uxmskyblock.core.domain.dimension.DimensionId;
import com.uxplima.uxmskyblock.core.domain.gamemode.PrimaryGameplayRootRef;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.snapshot.RestoreMode;
import com.uxplima.uxmskyblock.core.domain.storage.StorageBucket;
import org.jspecify.annotations.Nullable;

/**
 * Safety-critical restore engine coordinating fail-closed backup restore pipelines (Section 2.29).
 */
public final class IslandRestoreService {

    private final BackupCatalogPort catalogPort;
    private final ObjectStoragePort objectStoragePort;
    private final RootRelationalSnapshotPort relationalSnapshotPort;
    private final WorldDimensionSnapshotPort worldDimensionSnapshotPort;
    private @Nullable IslandAdminFreezeService quarantine;
    private @Nullable RestoreProgressPort progress;

    /** The catalogue line a visitor sees as the reason the island was closed to them. */
    public static final String QUARANTINE_REASON = "protection.quarantine_restoring";

    private static final String QUARANTINE_ACTOR = "restore";

    private static final Logger LOGGER = Logger.getLogger(IslandRestoreService.class.getName());

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

    /** The old shape, which now means the mode that changes least. */
    public RestoreOutcome executeRestore(
            BackupManifest manifest, StorageBucket bucket, String rootPrefix, boolean disasterRecoveryConfirmed) {
        return executeRestore(manifest, bucket, rootPrefix, disasterRecoveryConfirmed, RestoreMode.safeDefault());
    }

    /**
     * Puts a backup back, as far as {@code mode} allows.
     *
     * <p>The mode is the whole point of the boundary the persistence specification draws. Before it
     * existed this method restored every table the snapshot held, including the island bank, so an
     * administrator restoring last night's file handed back money that had already been spent.
     */
    public RestoreOutcome executeRestore(
            BackupManifest manifest,
            StorageBucket bucket,
            String rootPrefix,
            boolean disasterRecoveryConfirmed,
            RestoreMode mode) {
        Objects.requireNonNull(mode, "mode must not be null");

        Objects.requireNonNull(manifest, "manifest must not be null");
        Objects.requireNonNull(bucket, "bucket must not be null");
        Objects.requireNonNull(rootPrefix, "rootPrefix must not be null");

        // 1. Disaster recovery boundary guard.
        //
        // A whole database is never put back as a side effect of putting one island back. This used
        // to be a question the caller could answer yes to, and the one caller there is answers yes
        // to everything, so a backup set holding a whole database dump would have been handed to
        // the island relational restore as though it were one island's rows. Until the database
        // backup could be taken at all, nothing could reach this; it can be taken now.
        //
        // Putting a whole database back has its own service, its own confirmation code and its own
        // dialect check. It is not this door with a flag turned on.
        if (manifest.backupType() == BackupType.DATABASE_DISASTER_BACKUP) {
            return new RestoreOutcome.Failure("A whole database backup is never restored through an island restore");
        }

        String normalizedPrefix =
                rootPrefix.endsWith("/") ? rootPrefix.substring(0, rootPrefix.length() - 1) : rootPrefix;
        return restore(manifest, bucket, normalizedPrefix, mode, UUID.randomUUID(), Set.of(), false);
    }

    /**
     * Finishes every restore a stop interrupted, putting back only the units that were not yet back.
     *
     * <p>The persistence specification's crash replay. A unit written down as about to be put back and
     * never as back is put back again: putting a unit back replaces what is in the world with what the
     * backup holds, so doing it twice leaves the same island as doing it once. The island stays closed
     * from the interrupted run until this one commits, because the freeze is durable and its reason is
     * the restore's own.
     *
     * @param manifests finds a backup set's manifest by bucket and prefix
     */
    public List<RestoreOutcome> resumeUnfinished(
            StorageBucket bucket,
            java.util.function.BiFunction<StorageBucket, String, Optional<BackupManifest>> manifests) {
        Objects.requireNonNull(bucket, "bucket must not be null");
        Objects.requireNonNull(manifests, "manifests must not be null");
        RestoreProgressPort port = this.progress;
        if (port == null) {
            return List.of();
        }
        List<RestoreOutcome> outcomes = new ArrayList<>();
        for (RestoreProgressPort.RestoreOperation operation : port.unfinished()) {
            Optional<BackupManifest> manifest = manifests.apply(bucket, operation.sourcePrefix());
            if (manifest.isEmpty()) {
                // Half an island, and nothing left to finish it from. It stays closed for an
                // administrator to look at, rather than opening half put back.
                port.finish(
                        operation.restoreId(),
                        RestoreProgressPort.OperationState.FAILED,
                        "The backup set it was reading is gone");
                LOGGER.severe(() -> "Restore " + operation.restoreId() + " of island " + operation.islandId()
                        + " was interrupted and its backup set is gone. The island stays frozen.");
                outcomes.add(new RestoreOutcome.Failure("The backup set an interrupted restore was reading is gone"));
                continue;
            }
            outcomes.add(restore(
                    manifest.get(),
                    bucket,
                    operation.sourcePrefix(),
                    operation.mode(),
                    operation.restoreId(),
                    port.verifiedUnits(operation.restoreId()),
                    true));
        }
        return outcomes;
    }

    /** Writes each unit down before and after it is put back, so a stop between the two is resumed. */
    public void recordProgressIn(@Nullable RestoreProgressPort progressPort) {
        this.progress = progressPort;
    }

    private RestoreOutcome restore(
            BackupManifest manifest,
            StorageBucket bucket,
            String normalizedPrefix,
            RestoreMode mode,
            UUID restoreId,
            Set<String> alreadyBack,
            boolean resuming) {
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

        // Every artifact is read and checked before any of them is applied. Checking each one as it
        // was applied meant a bad second file stopped the restore with the first already written:
        // half an island put back, and an answer that said the restore had failed.
        List<Map.Entry<String, byte[]>> verified = new ArrayList<>();
        // In the order of their names, so a resumed restore walks the units the way the first run did.
        for (Map.Entry<String, BackupArtifact> entry : new java.util.TreeMap<>(manifest.artifacts()).entrySet()) {
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
            verified.add(Map.entry(filename, data));
        }

        // The world first and the rows last. The specification restores the island's configuration,
        // members and upgrades only after every world region is back, so an island whose world could
        // not be put back is never left with rows that describe a world it does not have.
        verified.sort(java.util.Comparator.comparing(entry -> isRelational(entry.getKey())));

        @Nullable IslandId quarantined = enterQuarantine(rootRef.rootId(), resuming);
        RestoreProgressPort port = this.progress;
        if (port != null && !resuming) {
            port.begin(new RestoreProgressPort.RestoreOperation(
                    restoreId, rootRef.rootId(), manifest.backupSetId(), normalizedPrefix, mode));
        }
        try {
            for (Map.Entry<String, byte[]> entry : verified) {
                String filename = entry.getKey();
                byte[] data = entry.getValue();
                // 4. Dispatch restoration based on artifact role
                boolean relational = isRelational(filename);
                boolean worldUnit = !relational
                        && (filename.contains("world") || filename.endsWith(".dat") || filename.endsWith(".zst"));
                if ((relational && !mode.restoresRelationalState()) || (!relational && !worldUnit)) {
                    continue;
                }
                String unit = unitIdOf(filename);
                if (alreadyBack.contains(unit)) {
                    restoredCount++;
                    continue;
                }
                if (port != null) {
                    port.intend(restoreId, unit, computeSha256(data));
                }
                if (relational) {
                    relationalSnapshotPort.restoreRelationalSnapshot(rootRef, data, mode);
                } else {
                    DimensionId dimId = filename.contains("nether")
                            ? DimensionId.THE_NETHER
                            : (filename.contains("end") ? DimensionId.THE_END : DimensionId.OVERWORLD);
                    worldDimensionSnapshotPort.restoreWorldDimension(rootRef, dimId, data);
                }
                if (port != null) {
                    port.verified(restoreId, unit);
                }
                restoredCount++;
            }
            if (port != null) {
                port.finish(restoreId, RestoreProgressPort.OperationState.COMMITTED, null);
            }
        } catch (RuntimeException failed) {
            // A unit that threw, rather than a node that stopped: there is nothing to resume, so the
            // restore is written down as failed and the island opens, as it did before.
            if (port != null) {
                port.finish(restoreId, RestoreProgressPort.OperationState.FAILED, reasonOf(failed));
            }
            throw failed;
        } finally {
            leaveQuarantine(quarantined);
        }

        return new RestoreOutcome.Success(manifest.backupSetId(), restoredCount);
    }

    /**
     * Freezes each island for as long as it is being put back.
     *
     * <p>A restore works through the island a slice at a time, over many ticks, and a player on it
     * kept playing: an item put into a chest the restore had not reached yet was cleared with the
     * chest a moment later. The persistence specification locks an island that is being restored,
     * sends its visitors away and blocks every edit, and the freeze is exactly that lock.
     */
    public void quarantineWith(@Nullable IslandAdminFreezeService freezeService) {
        this.quarantine = freezeService;
    }

    /**
     * Freezes the island, unless there is no island to freeze or an administrator already froze it.
     * A freeze an administrator made is theirs to lift, so a restore that did not make it leaves it.
     */
    private @Nullable IslandId enterQuarantine(String rootKey, boolean resuming) {
        IslandAdminFreezeService freezeService = this.quarantine;
        if (freezeService == null) {
            return null;
        }
        IslandId islandId;
        try {
            islandId = IslandId.fromString(rootKey);
        } catch (IllegalArgumentException notAnIsland) {
            return null;
        }
        if (freezeService.isFrozen(islandId)) {
            // A restore picked up after a stop finds the island still closed by the run it interrupted.
            // That freeze is the restore's own, so it lifts it when it commits; any other is left.
            boolean ours = resuming
                    && freezeService
                            .getFreezeRecord(islandId)
                            .map(record -> QUARANTINE_REASON.equals(record.freezeReason()))
                            .orElse(false);
            return ours ? islandId : null;
        }
        try {
            freezeService.freezeIsland(islandId, QUARANTINE_REASON, QUARANTINE_ACTOR);
            return islandId;
        } catch (IllegalArgumentException | IllegalStateException nothingToFreeze) {
            // The island is gone or not in play, so nobody is on it to protect.
            return null;
        }
    }

    private void leaveQuarantine(@Nullable IslandId islandId) {
        IslandAdminFreezeService freezeService = this.quarantine;
        if (islandId == null || freezeService == null) {
            return;
        }
        try {
            freezeService.unfreezeIsland(islandId, QUARANTINE_ACTOR);
        } catch (RuntimeException e) {
            LOGGER.log(
                    Level.WARNING, "Island " + islandId + " was restored and is still frozen. Unfreeze it by hand.", e);
        }
    }

    /** Whether an artifact holds the island's rows rather than a piece of its world. */
    private static boolean isRelational(String filename) {
        return filename.contains("relational") || filename.endsWith(".sql") || filename.endsWith(".json");
    }

    /** A unit's name in the progress table, which holds 64 characters. */
    private static String unitIdOf(String filename) {
        return filename.length() <= 64 ? filename : computeSha256(filename.getBytes(StandardCharsets.UTF_8));
    }

    private static String reasonOf(RuntimeException failed) {
        String reason = String.valueOf(failed.getMessage());
        return reason.length() <= 255 ? reason : reason.substring(0, 255);
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
