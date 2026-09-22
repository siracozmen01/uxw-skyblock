package com.uxplima.uxmskyblock.core.application.backup;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.uxplima.uxmskyblock.core.application.storage.ObjectStoragePort;
import com.uxplima.uxmskyblock.core.domain.backup.BackupArtifact;
import com.uxplima.uxmskyblock.core.domain.backup.BackupCatalogRecord;
import com.uxplima.uxmskyblock.core.domain.backup.BackupLifecycleState;
import com.uxplima.uxmskyblock.core.domain.backup.BackupManifest;
import com.uxplima.uxmskyblock.core.domain.backup.BackupSetId;
import com.uxplima.uxmskyblock.core.domain.backup.BackupType;
import com.uxplima.uxmskyblock.core.domain.backup.DatabaseBackupDialect;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.storage.StorageBucket;
import org.jspecify.annotations.Nullable;

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

    /** How long a confirmation code stays good, which is as long as the island reset gives. */
    public static final Duration DEFAULT_CHALLENGE_TTL = Duration.ofSeconds(60);

    private record Pending(BackupSetId backupSetId, String code, Instant expiresAt) {}

    private final BackupService backupService;
    private final DatabaseBackupPort databaseBackupPort;
    private final String pluginVersion;
    private final Clock clock;
    private final Duration challengeTtl;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<ProfileId, Pending> pending = new ConcurrentHashMap<>();

    public DatabaseDisasterBackupService(
            BackupService backupService, DatabaseBackupPort databaseBackupPort, String pluginVersion) {
        this(backupService, databaseBackupPort, pluginVersion, Clock.systemUTC(), DEFAULT_CHALLENGE_TTL);
    }

    public DatabaseDisasterBackupService(
            BackupService backupService,
            DatabaseBackupPort databaseBackupPort,
            String pluginVersion,
            Clock clock,
            Duration challengeTtl) {
        this.backupService = Objects.requireNonNull(backupService, "backupService must not be null");
        this.databaseBackupPort = Objects.requireNonNull(databaseBackupPort, "databaseBackupPort must not be null");
        this.pluginVersion = Objects.requireNonNull(pluginVersion, "pluginVersion must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.challengeTtl = Objects.requireNonNull(challengeTtl, "challengeTtl must not be null");
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

    /** What came of asking to put one back. */
    public sealed interface RestoreOutcome {

        /** A code was issued and nothing has been written yet. */
        record CodeIssued(String code, Instant expiresAt) implements RestoreOutcome {}

        /** The database was written over from the backup. */
        record Restored(BackupSetId backupSetId, long bytes) implements RestoreOutcome {}

        /** It was refused, and this is why. */
        record Refused(String reason) implements RestoreOutcome {
            public Refused {
                Objects.requireNonNull(reason, "reason must not be null");
            }
        }
    }

    /**
     * Issues the code that has to be typed back before a whole database is written over.
     *
     * <p>Restoring one of these replaces every island, every bank and every inventory on the
     * server at once. An island reset asks for four digits before it erases one island, so this
     * asks for four before it erases all of them.
     */
    public RestoreOutcome requestRestore(ProfileId requester, BackupSetId backupSetId) {
        Objects.requireNonNull(requester, "requester must not be null");
        Objects.requireNonNull(backupSetId, "backupSetId must not be null");

        String code = String.format("%04d", secureRandom.nextInt(10000));
        Instant expiresAt = clock.instant().plus(challengeTtl);
        pending.put(requester, new Pending(backupSetId, code, expiresAt));
        return new RestoreOutcome.CodeIssued(code, expiresAt);
    }

    /** Throws away whatever code this requester was given. */
    public void cancelRestore(ProfileId requester) {
        Objects.requireNonNull(requester, "requester must not be null");
        pending.remove(requester);
    }

    /**
     * Puts the whole database back, if the code is the one that was issued for this backup.
     *
     * <p>A backup set that is not a disaster backup is refused here rather than read: the
     * specification says a whole database is never restored as a side effect of putting one island
     * back, and the same sentence read the other way says an island's rows are not a database.
     *
     * <p>A backup taken from another dialect is refused too. The dump is written in the words of
     * the database it came out of and there is no honest way to read it into a different one.
     */
    public RestoreOutcome restoreDatabase(
            StorageBucket bucket, BackupSetId backupSetId, ProfileId requester, @Nullable String code) {
        Objects.requireNonNull(bucket, "bucket must not be null");
        Objects.requireNonNull(backupSetId, "backupSetId must not be null");
        Objects.requireNonNull(requester, "requester must not be null");

        Pending held = pending.get(requester);
        if (held == null) {
            return new RestoreOutcome.Refused("No restore was asked for");
        }
        if (clock.instant().isAfter(held.expiresAt())) {
            pending.remove(requester);
            return new RestoreOutcome.Refused("The code ran out");
        }
        if (!held.backupSetId().equals(backupSetId) || !held.code().equals(code == null ? "" : code.trim())) {
            return new RestoreOutcome.Refused("That is not the code for that backup");
        }
        pending.remove(requester);

        String prefix = prefixFor(backupSetId);
        Optional<BackupManifest> optManifest = backupService.loadManifest(bucket, prefix);
        if (optManifest.isEmpty()) {
            return new RestoreOutcome.Refused("No manifest for backup " + backupSetId);
        }
        BackupManifest manifest = optManifest.get();
        if (manifest.backupType() != BackupType.DATABASE_DISASTER_BACKUP) {
            return new RestoreOutcome.Refused("Backup " + backupSetId + " is not a whole database backup");
        }

        if (!markerIsThere(bucket, prefix)) {
            return new RestoreOutcome.Refused("Backup " + backupSetId + " never finished publishing");
        }

        BackupArtifact expected = manifest.artifacts().get(DATABASE_ARTIFACT);
        if (expected == null) {
            return new RestoreOutcome.Refused("The manifest names no database artifact");
        }
        Optional<byte[]> optDump = read(bucket, prefix + "/" + DATABASE_ARTIFACT);
        if (optDump.isEmpty()) {
            return new RestoreOutcome.Refused("The database artifact is not in storage");
        }
        byte[] dump = optDump.get();
        if (!BackupService.computeSha256(dump).equalsIgnoreCase(expected.sha256Checksum())) {
            return new RestoreOutcome.Refused("The database artifact does not match its checksum");
        }

        DatabaseBackupDialect live = databaseBackupPort.liveDialect();
        if (!live.name().equals(manifest.rootKey())) {
            return new RestoreOutcome.Refused(
                    "That backup came out of a " + manifest.rootKey() + " database and this one is " + live);
        }

        try {
            databaseBackupPort.restoreDatabaseBackup(dump, live, true);
        } catch (RuntimeException failed) {
            return new RestoreOutcome.Refused("The restore failed: " + failed.getMessage());
        }
        return new RestoreOutcome.Restored(backupSetId, dump.length);
    }

    private boolean markerIsThere(StorageBucket bucket, String prefix) {
        String key = prefix + "/" + BackupService.AVAILABILITY_MARKER_FILE_NAME;
        for (ObjectStoragePort destination : backupService.storageDestinations()) {
            if (destination.exists(bucket, key)) {
                return true;
            }
        }
        return false;
    }

    private Optional<byte[]> read(StorageBucket bucket, String key) {
        for (ObjectStoragePort destination : backupService.storageDestinations()) {
            Optional<byte[]> found = destination.getObject(bucket, key);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /** Where a disaster backup is written, kept apart from the per island ones. */
    public static String prefixFor(BackupSetId backupSetId) {
        Objects.requireNonNull(backupSetId, "backupSetId must not be null");
        return "database/" + backupSetId;
    }
}
