package com.uxplima.uxmskyblock.core.application.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.uxplima.uxmskyblock.core.domain.backup.BackupCatalogRecord;
import com.uxplima.uxmskyblock.core.domain.backup.BackupManifest;
import com.uxplima.uxmskyblock.core.domain.backup.BackupType;
import com.uxplima.uxmskyblock.core.domain.backup.DatabaseBackupDialect;
import com.uxplima.uxmskyblock.core.domain.storage.StorageBucket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The whole database can be taken out, for the day the database is gone.
 *
 * <p>The persistence specification publishes a second kind of backup beside the per island one and
 * the catalog table has a column with its word in it. The port was written, an adapter implemented
 * it dialect by dialect, and nothing anywhere called either half: an operator could not take one
 * and so could never restore one.
 */
class TheWholeDatabaseCanBeBackedUpTest {

    private static final StorageBucket BUCKET = new StorageBucket("backups");
    private static final byte[] DUMP = "-- SKYBLOCK_DISASTER_BACKUP_V1:SQLITE\n".getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("The capture is published as a disaster backup, under the live dialect")
    void thecaptureIsPublishedAsADisasterBackup() {
        BackupService publisher = mock(BackupService.class);
        when(publisher.publishBackup(any(), any(), any(), any(), any())).thenReturn(true);
        DatabaseBackupPort port = mock(DatabaseBackupPort.class);
        when(port.liveDialect()).thenReturn(DatabaseBackupDialect.POSTGRESQL);
        when(port.captureDatabaseBackup(DatabaseBackupDialect.POSTGRESQL)).thenReturn(DUMP);

        DatabaseDisasterBackupService.Outcome outcome =
                new DatabaseDisasterBackupService(publisher, port, "1.0.0").backupDatabase(BUCKET);

        assertThat(outcome).isInstanceOf(DatabaseDisasterBackupService.Outcome.Success.class);
        ArgumentCaptor<BackupManifest> manifest = ArgumentCaptor.forClass(BackupManifest.class);
        ArgumentCaptor<BackupCatalogRecord> record = ArgumentCaptor.forClass(BackupCatalogRecord.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, byte[]>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(publisher).publishBackup(any(), any(), record.capture(), manifest.capture(), payloads.capture());

        assertThat(manifest.getValue().backupType())
                .describedAs("the word the catalog table has published all along")
                .isEqualTo(BackupType.DATABASE_DISASTER_BACKUP);
        assertThat(record.getValue().backupType()).isEqualTo(BackupType.DATABASE_DISASTER_BACKUP);
        assertThat(payloads.getValue()).containsOnlyKeys(DatabaseDisasterBackupService.DATABASE_ARTIFACT);
        assertThat(payloads.getValue().get(DatabaseDisasterBackupService.DATABASE_ARTIFACT))
                .isEqualTo(DUMP);
    }

    @Test
    @DisplayName("The dialect is the live one, not one the caller had to know")
    void thedialectIsTheLiveOne() {
        BackupService publisher = mock(BackupService.class);
        when(publisher.publishBackup(any(), any(), any(), any(), any())).thenReturn(true);
        DatabaseBackupPort port = mock(DatabaseBackupPort.class);
        when(port.liveDialect()).thenReturn(DatabaseBackupDialect.MARIADB);
        when(port.captureDatabaseBackup(any())).thenReturn(DUMP);

        DatabaseDisasterBackupService.Outcome outcome =
                new DatabaseDisasterBackupService(publisher, port, "1.0.0").backupDatabase(BUCKET);

        verify(port).captureDatabaseBackup(DatabaseBackupDialect.MARIADB);
        assertThat(outcome)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(
                        DatabaseDisasterBackupService.Outcome.Success.class))
                .extracting(DatabaseDisasterBackupService.Outcome.Success::dialect)
                .isEqualTo(DatabaseBackupDialect.MARIADB);
    }

    @Test
    @DisplayName("A capture that throws is a refusal, not a catalog row that sits there for ever")
    void afailedCaptureIsARefusal() {
        BackupService publisher = mock(BackupService.class);
        DatabaseBackupPort port = mock(DatabaseBackupPort.class);
        when(port.liveDialect()).thenReturn(DatabaseBackupDialect.SQLITE);
        when(port.captureDatabaseBackup(any())).thenThrow(new IllegalStateException("the disk is full"));

        DatabaseDisasterBackupService.Outcome outcome =
                new DatabaseDisasterBackupService(publisher, port, "1.0.0").backupDatabase(BUCKET);

        assertThat(outcome).isInstanceOf(DatabaseDisasterBackupService.Outcome.Failure.class);
        verify(publisher, never()).publishBackup(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("A publication that does not land everywhere is a refusal too")
    void afailedPublicationIsARefusal() {
        BackupService publisher = mock(BackupService.class);
        when(publisher.publishBackup(any(), any(), any(), any(), any())).thenReturn(false);
        DatabaseBackupPort port = mock(DatabaseBackupPort.class);
        when(port.liveDialect()).thenReturn(DatabaseBackupDialect.SQLITE);
        when(port.captureDatabaseBackup(any())).thenReturn(DUMP);

        assertThat(new DatabaseDisasterBackupService(publisher, port, "1.0.0").backupDatabase(BUCKET))
                .isInstanceOf(DatabaseDisasterBackupService.Outcome.Failure.class);
    }

    @Test
    @DisplayName("A disaster backup is written apart from the island ones, so neither can hide the other")
    void adisasterBackupHasItsOwnPlace() {
        assertThat(DatabaseDisasterBackupService.prefixFor(
                        com.uxplima.uxmskyblock.core.domain.backup.BackupSetId.random()))
                .startsWith("database/");
    }
}
