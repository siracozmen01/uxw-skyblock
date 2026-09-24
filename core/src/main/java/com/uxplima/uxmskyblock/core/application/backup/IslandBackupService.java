package com.uxplima.uxmskyblock.core.application.backup;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.application.snapshot.RootRelationalSnapshotPort;
import com.uxplima.uxmskyblock.core.application.snapshot.WorldDimensionSnapshotPort;
import com.uxplima.uxmskyblock.core.domain.backup.BackupArtifact;
import com.uxplima.uxmskyblock.core.domain.backup.BackupCatalogRecord;
import com.uxplima.uxmskyblock.core.domain.backup.BackupLifecycleState;
import com.uxplima.uxmskyblock.core.domain.backup.BackupManifest;
import com.uxplima.uxmskyblock.core.domain.backup.BackupSetId;
import com.uxplima.uxmskyblock.core.domain.backup.BackupType;
import com.uxplima.uxmskyblock.core.domain.dimension.DimensionId;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeInstanceId;
import com.uxplima.uxmskyblock.core.domain.gamemode.PrimaryGameplayRootRef;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.storage.StorageBucket;

/**
 * Makes the backup that {@code /is admin restore} puts back.
 *
 * <p>Everything on the reading side has been here since the enterprise foundation work: the catalog
 * table, the manifest, the AVAILABLE marker ordering, the SHA-256 verification, the local and S3
 * destinations, the restore modes. Nothing ever wrote one. {@link BackupService#publishBackup} had
 * no caller anywhere in the plugin, so an administrator could restore backups that could not exist,
 * and the first thing they would learn about it is the day they needed one.
 *
 * <p>The artifact names are the ones the restore reads. It dispatches on the filename, sending
 * anything holding "relational" to the relational port and anything holding "world" to the world
 * port, so the two halves have to agree on the words and this is where they do.
 */
public final class IslandBackupService {

    /** The filename the restore recognises as this island's relational rows. */
    public static final String RELATIONAL_ARTIFACT = "relational.json";

    /** The dimensions a backup carries, and the filename each one is written under. */
    private static final Map<DimensionId, String> WORLD_ARTIFACTS = Map.of(
            DimensionId.OVERWORLD, "world-overworld.dat",
            DimensionId.THE_NETHER, "world-nether.dat",
            DimensionId.THE_END, "world-end.dat");

    /** What a request to back an island up came back with. */
    public sealed interface BackupOutcome {

        /** The backup is published and the catalog has it as AVAILABLE. */
        record Success(BackupSetId backupSetId, int artifacts) implements BackupOutcome {}

        /** It reached some destinations and not the others, and is recorded as partial. */
        record Partial(BackupSetId backupSetId) implements BackupOutcome {}

        /** Nothing was published, and {@code reason} says why. */
        record Failure(String reason) implements BackupOutcome {}
    }

    private final BackupService backupService;
    private final RootRelationalSnapshotPort relationalSnapshotPort;
    private final WorldDimensionSnapshotPort worldDimensionSnapshotPort;
    private final String pluginVersion;

    public IslandBackupService(
            BackupService backupService,
            RootRelationalSnapshotPort relationalSnapshotPort,
            WorldDimensionSnapshotPort worldDimensionSnapshotPort,
            String pluginVersion) {
        this.backupService = Objects.requireNonNull(backupService, "backupService must not be null");
        this.relationalSnapshotPort =
                Objects.requireNonNull(relationalSnapshotPort, "relationalSnapshotPort must not be null");
        this.worldDimensionSnapshotPort =
                Objects.requireNonNull(worldDimensionSnapshotPort, "worldDimensionSnapshotPort must not be null");
        this.pluginVersion = Objects.requireNonNull(pluginVersion, "pluginVersion must not be null");
    }

    /** The prefix a backup set of this island is written under, and read back from. */
    public static String prefixFor(BackupSetId backupSetId) {
        return "backups/" + backupSetId;
    }

    /**
     * Captures one island and publishes it as a backup set.
     *
     * <p>This reads the database and every chunk inside the island's bounds, so it belongs on the
     * scheduler and never on a thread that owns a player.
     *
     * @param islandId the island to capture
     * @param bucket where to publish, which is the bucket the operator named
     * @param dimensions which dimensions to capture, in the order they should be written
     */
    public BackupOutcome backupIsland(IslandId islandId, StorageBucket bucket, List<DimensionId> dimensions) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(bucket, "bucket must not be null");
        Objects.requireNonNull(dimensions, "dimensions must not be null");

        BackupSetId backupSetId = BackupSetId.random();
        Instant now = Instant.now();
        String rootKey = islandId.value().toString();
        PrimaryGameplayRootRef rootRef =
                new PrimaryGameplayRootRef(GameModeInstanceId.fromString(rootKey), rootKey, "ISLAND", now);

        Map<String, byte[]> payloads = new LinkedHashMap<>();
        try {
            payloads.put(RELATIONAL_ARTIFACT, relationalSnapshotPort.captureRelationalSnapshot(rootRef, 1L));
            for (DimensionId dimension : dimensions) {
                String filename = WORLD_ARTIFACTS.get(dimension);
                if (filename == null) {
                    continue;
                }
                payloads.put(filename, worldDimensionSnapshotPort.captureWorldDimension(rootRef, dimension));
            }
        } catch (RuntimeException capture) {
            // Nothing has been written yet, so there is no half published set to clean up. Saying
            // why beats a catalog row that sits in CAPTURING for ever.
            return new BackupOutcome.Failure("Capture failed: " + capture.getMessage());
        }

        Map<String, BackupArtifact> artifacts = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : payloads.entrySet()) {
            artifacts.put(
                    entry.getKey(),
                    new BackupArtifact(
                            entry.getKey(), entry.getValue().length, BackupService.computeSha256(entry.getValue())));
        }

        BackupManifest manifest = new BackupManifest(
                backupSetId,
                BackupType.ROOT_BACKUP,
                "ISLAND",
                rootKey,
                now,
                1L,
                1L,
                1,
                pluginVersion,
                artifacts,
                "CONSISTENT");

        BackupCatalogRecord record = new BackupCatalogRecord(
                backupSetId,
                BackupType.ROOT_BACKUP,
                "ISLAND",
                rootKey,
                BackupLifecycleState.PLANNED,
                1L,
                1L,
                1,
                pluginVersion,
                null,
                now,
                null,
                now);

        boolean published = backupService.publishBackup(bucket, prefixFor(backupSetId), record, manifest, payloads);
        if (published) {
            return new BackupOutcome.Success(backupSetId, artifacts.size());
        }
        return backupService.isPartial(backupSetId)
                ? new BackupOutcome.Partial(backupSetId)
                : new BackupOutcome.Failure("Publication failed; the catalog record says why");
    }
}
