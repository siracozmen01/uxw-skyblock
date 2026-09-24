package com.uxplima.uxmskyblock.core.application.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.storage.ObjectStoragePort;
import com.uxplima.uxmskyblock.core.domain.backup.BackupArtifact;
import com.uxplima.uxmskyblock.core.domain.backup.BackupManifest;
import com.uxplima.uxmskyblock.core.domain.backup.BackupSetId;
import com.uxplima.uxmskyblock.core.domain.backup.BackupType;
import com.uxplima.uxmskyblock.core.domain.backup.DatabaseBackupDialect;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.storage.StorageBucket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Putting a whole database back is its own act, asked for and confirmed, and it takes the whole database.
 *
 * <p>The game mode architecture names this test. A restore of the relational database never runs on
 * the strength of a backup id alone: it needs the code issued for that backup typed back, it refuses
 * a backup of one island, and when it runs it hands the whole dump to the database, which is what
 * sets it apart from putting one island back.
 */
class DatabaseDisasterBackupRestoreScopeTest {

    private static final StorageBucket BUCKET = new StorageBucket("backups");
    private static final byte[] DUMP =
            "-- SKYBLOCK_DISASTER_BACKUP_V1:SQLITE\nall of it".getBytes(StandardCharsets.UTF_8);
    private final ProfileId admin = ProfileId.of(UUID.randomUUID());

    @Test
    @DisplayName("Without a confirmed code nothing is restored, whatever the backup")
    void nothingWithoutAConfirmation() {
        BackupSetId setId = BackupSetId.random();
        DatabaseBackupPort database = sqliteDatabase();
        DatabaseDisasterBackupService service = new DatabaseDisasterBackupService(
                holding(setId, BackupType.DATABASE_DISASTER_BACKUP), database, "1.0.0");

        assertThat(service.restoreDatabase(BUCKET, setId, admin, "1234"))
                .describedAs("a code nobody asked for")
                .isInstanceOf(DatabaseDisasterBackupService.RestoreOutcome.Refused.class);
        verify(database, never()).restoreDatabaseBackup(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("An island's backup is never put back as a whole database, even with the code")
    void anIslandBackupIsNotADatabase() {
        BackupSetId setId = BackupSetId.random();
        DatabaseBackupPort database = sqliteDatabase();
        DatabaseDisasterBackupService service =
                new DatabaseDisasterBackupService(holding(setId, BackupType.ROOT_BACKUP), database, "1.0.0");
        String code = issued(service.requestRestore(admin, setId));

        assertThat(service.restoreDatabase(BUCKET, setId, admin, code))
                .isInstanceOf(DatabaseDisasterBackupService.RestoreOutcome.Refused.class);
        verify(database, never()).restoreDatabaseBackup(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("A confirmed disaster restore hands the whole dump to the database, marked as confirmed")
    void aConfirmedRestoreTakesTheWholeDatabase() {
        BackupSetId setId = BackupSetId.random();
        DatabaseBackupPort database = sqliteDatabase();
        DatabaseDisasterBackupService service = new DatabaseDisasterBackupService(
                holding(setId, BackupType.DATABASE_DISASTER_BACKUP), database, "1.0.0");
        String code = issued(service.requestRestore(admin, setId));

        assertThat(service.restoreDatabase(BUCKET, setId, admin, code))
                .isInstanceOf(DatabaseDisasterBackupService.RestoreOutcome.Restored.class);
        verify(database).restoreDatabaseBackup(DUMP, DatabaseBackupDialect.SQLITE, true);
    }

    private static DatabaseBackupPort sqliteDatabase() {
        DatabaseBackupPort port = mock(DatabaseBackupPort.class);
        when(port.liveDialect()).thenReturn(DatabaseBackupDialect.SQLITE);
        return port;
    }

    private static String issued(DatabaseDisasterBackupService.RestoreOutcome outcome) {
        assertThat(outcome).isInstanceOf(DatabaseDisasterBackupService.RestoreOutcome.CodeIssued.class);
        return ((DatabaseDisasterBackupService.RestoreOutcome.CodeIssued) outcome).code();
    }

    /** Storage holding a published backup of {@code type} whose one artifact is the dump. */
    private static BackupService holding(BackupSetId setId, BackupType type) {
        ObjectStoragePort destination = mock(ObjectStoragePort.class);
        String prefix = DatabaseDisasterBackupService.prefixFor(setId);
        when(destination.exists(any(), any())).thenReturn(true);
        when(destination.getObject(
                        any(),
                        org.mockito.ArgumentMatchers.eq(
                                prefix + "/" + DatabaseDisasterBackupService.DATABASE_ARTIFACT)))
                .thenReturn(Optional.of(DUMP));
        BackupService publisher = mock(BackupService.class);
        when(publisher.storageDestinations()).thenReturn(List.of(destination));
        when(publisher.loadManifest(any(), any()))
                .thenReturn(Optional.of(new BackupManifest(
                        setId,
                        type,
                        "DATABASE",
                        "SQLITE",
                        Instant.now(),
                        1L,
                        1L,
                        1,
                        "1.0.0",
                        Map.of(
                                DatabaseDisasterBackupService.DATABASE_ARTIFACT,
                                new BackupArtifact(
                                        DatabaseDisasterBackupService.DATABASE_ARTIFACT,
                                        DUMP.length,
                                        BackupService.computeSha256(DUMP))),
                        "CONSISTENT")));
        return publisher;
    }
}
