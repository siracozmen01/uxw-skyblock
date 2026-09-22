package com.uxplima.uxmskyblock.core.application.backup;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.backup.BackupArtifact;
import com.uxplima.uxmskyblock.core.domain.backup.BackupCatalogRecord;
import com.uxplima.uxmskyblock.core.domain.backup.BackupLifecycleState;
import com.uxplima.uxmskyblock.core.domain.backup.BackupManifest;
import com.uxplima.uxmskyblock.core.domain.backup.BackupSetId;
import com.uxplima.uxmskyblock.core.domain.backup.BackupType;
import com.uxplima.uxmskyblock.core.domain.backup.DatabaseBackupDialect;
import com.uxplima.uxmskyblock.core.domain.storage.StorageBucket;

/**
 * Takes the whole database out, for the day the database is gone.
 *
 * <p>The persistence specification publishes a second kind of backup beside the per island one:
 * {@code DATABASE_DISASTER_BACKUP}, driven by {@link DatabaseBackupPort}. The port was written, an
 * adapter implemented it dialect by dialect, the catalog table has a column with the word in it,
 * and nothing anywhere called either half. An operator could not take one and so could never
 * restore one, and the day they find out is the day they needed it.
 *
 * <p>It goes out through the same publication as an island backup: catalog row first, artifact
 * next, manifest after that, checksums verified, and the AVAILABLE marker strictly last. A
 * disaster backup that is discovered without a live database is discovered by those files, so it
 * has to be written the same way.
 */
public final class DatabaseDisasterBackupService {

    /** The filename the artifact is published under. */
    public static final String DATABASE_ARTIFACT = "database.sql";

    /** What came of asking for one. */
    public sealed interface Outcome {

        /** It was captured, published and verified everywhere it was sent. */
        record Success(BackupSetId backupSetId, DatabaseBackupDialect dialect, long bytes) implements Outcome {}

        /** It was not, and this is why. */
        record Failure(String reason) implements Outcome {
            public Failure {
                Objects.requireNonNull(reason, "reason must not be null");
            }
        }
    }

    private final BackupService backupService;
    private final DatabaseBackupPort databaseBackupPort;
    private final String pluginVersion;

    public DatabaseDisasterBackupService(
            BackupService backupService, DatabaseBackupPort databaseBackupPort, String pluginVersion) {
        this.backupService = Objects.requireNonNull(backupService, "backupService must not be null");
        this.databaseBackupPort = Objects.requireNonNull(databaseBackupPort, "databaseBackupPort must not be null");
        this.pluginVersion = Objects.requireNonNull(pluginVersion, "pluginVersion must not be null");
    }

    /**
     * Captures the whole database and publishes it as one backup set.
     *
     * <p>The dialect is the live one rather than one the caller names, because an export written
     * for the wrong dialect is only wrong on the day it is restored.
     */
    public Outcome backupDatabase(StorageBucket bucket) {
        Objects.requireNonNull(bucket, "bucket must not be null");

        BackupSetId backupSetId = BackupSetId.random();
        Instant now = Instant.now();

        DatabaseBackupDialect dialect;
        byte[] dump;
        try {
            dialect = databaseBackupPort.liveDialect();
            dump = databaseBackupPort.captureDatabaseBackup(dialect);
        } catch (RuntimeException capture) {
            // Nothing has been written yet, so there is no half published set to clean up.
            return new Outcome.Failure("Capture failed: " + capture.getMessage());
        }

        Map<String, byte[]> payloads = new LinkedHashMap<>();
        payloads.put(DATABASE_ARTIFACT, dump);

        Map<String, BackupArtifact> artifacts = new LinkedHashMap<>();
        artifacts.put(
                DATABASE_ARTIFACT,
                new BackupArtifact(DATABASE_ARTIFACT, dump.length, BackupService.computeSha256(dump)));

        String rootKey = dialect.name();
        BackupManifest manifest = new BackupManifest(
                backupSetId,
                BackupType.DATABASE_DISASTER_BACKUP,
                "DATABASE",
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
                BackupType.DATABASE_DISASTER_BACKUP,
                "DATABASE",
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
        return published
                ? new Outcome.Success(backupSetId, dialect, dump.length)
                : new Outcome.Failure("Publication failed; the catalog record says why");
    }

    /** Where a disaster backup is written, kept apart from the per island ones. */
    public static String prefixFor(BackupSetId backupSetId) {
        Objects.requireNonNull(backupSetId, "backupSetId must not be null");
        return "database/" + backupSetId;
    }
}
