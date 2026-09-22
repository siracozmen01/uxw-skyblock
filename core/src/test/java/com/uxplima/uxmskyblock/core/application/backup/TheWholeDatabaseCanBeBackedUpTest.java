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

    /** A publisher that finds this manifest, this marker and this artifact in storage. */
    private static BackupService publisherHolding(
            com.uxplima.uxmskyblock.core.domain.backup.BackupSetId backupSetId,
            BackupType type,
            String dialectName,
            byte[] artifact,
            boolean markerThere) {
        com.uxplima.uxmskyblock.core.application.storage.ObjectStoragePort destination =
                mock(com.uxplima.uxmskyblock.core.application.storage.ObjectStoragePort.class);
        String prefix = DatabaseDisasterBackupService.prefixFor(backupSetId);
        when(destination.exists(any(), any())).thenReturn(markerThere);
        when(destination.getObject(
                        any(),
                        org.mockito.ArgumentMatchers.eq(
                                prefix + "/" + DatabaseDisasterBackupService.DATABASE_ARTIFACT)))
                .thenReturn(java.util.Optional.of(artifact));

        BackupService publisher = mock(BackupService.class);
        when(publisher.storageDestinations()).thenReturn(java.util.List.of(destination));
        when(publisher.loadManifest(any(), any()))
                .thenReturn(java.util.Optional.of(new BackupManifest(
                        backupSetId,
                        type,
                        "DATABASE",
                        dialectName,
                        java.time.Instant.now(),
                        1L,
                        1L,
                        1,
                        "1.0.0",
                        Map.of(
                                DatabaseDisasterBackupService.DATABASE_ARTIFACT,
                                new com.uxplima.uxmskyblock.core.domain.backup.BackupArtifact(
                                        DatabaseDisasterBackupService.DATABASE_ARTIFACT,
                                        artifact.length,
                                        BackupService.computeSha256(artifact))),
                        "CONSISTENT")));
        return publisher;
    }

    @Test
    @DisplayName("Putting one back asks for four digits first, and does nothing until they come back")
    void puttingOneBackAsksFirst() {
        var backupSetId = com.uxplima.uxmskyblock.core.domain.backup.BackupSetId.random();
        DatabaseBackupPort port = mock(DatabaseBackupPort.class);
        when(port.liveDialect()).thenReturn(DatabaseBackupDialect.SQLITE);
        DatabaseDisasterBackupService service = new DatabaseDisasterBackupService(
                publisherHolding(backupSetId, BackupType.DATABASE_DISASTER_BACKUP, "SQLITE", DUMP, true),
                port,
                "1.0.0");
        var requester = com.uxplima.uxmskyblock.core.domain.identity.ProfileId.of(java.util.UUID.randomUUID());

        var issued = service.requestRestore(requester, backupSetId);

        assertThat(issued)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(
                        DatabaseDisasterBackupService.RestoreOutcome.CodeIssued.class))
                .extracting(DatabaseDisasterBackupService.RestoreOutcome.CodeIssued::code)
                .asString()
                .hasSize(4);
        verify(port, never()).restoreDatabaseBackup(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());

        var done = service.restoreDatabase(
                BUCKET,
                backupSetId,
                requester,
                ((DatabaseDisasterBackupService.RestoreOutcome.CodeIssued) issued).code());

        assertThat(done).isInstanceOf(DatabaseDisasterBackupService.RestoreOutcome.Restored.class);
        verify(port).restoreDatabaseBackup(DUMP, DatabaseBackupDialect.SQLITE, true);
    }

    @Test
    @DisplayName("The wrong four digits write nothing")
    void thewrongCodeWritesNothing() {
        var backupSetId = com.uxplima.uxmskyblock.core.domain.backup.BackupSetId.random();
        DatabaseBackupPort port = mock(DatabaseBackupPort.class);
        when(port.liveDialect()).thenReturn(DatabaseBackupDialect.SQLITE);
        DatabaseDisasterBackupService service = new DatabaseDisasterBackupService(
                publisherHolding(backupSetId, BackupType.DATABASE_DISASTER_BACKUP, "SQLITE", DUMP, true),
                port,
                "1.0.0");
        var requester = com.uxplima.uxmskyblock.core.domain.identity.ProfileId.of(java.util.UUID.randomUUID());
        var unused = service.requestRestore(requester, backupSetId);

        assertThat(service.restoreDatabase(BUCKET, backupSetId, requester, "0000"))
                .isInstanceOf(DatabaseDisasterBackupService.RestoreOutcome.Refused.class);
        assertThat(service.restoreDatabase(BUCKET, backupSetId, requester, null))
                .describedAs("and no code at all is not a code")
                .isInstanceOf(DatabaseDisasterBackupService.RestoreOutcome.Refused.class);
    }

    @Test
    @DisplayName("A backup out of another dialect is refused, because the dump is written in its words")
    void anotherDialectIsRefused() {
        var backupSetId = com.uxplima.uxmskyblock.core.domain.backup.BackupSetId.random();
        DatabaseBackupPort port = mock(DatabaseBackupPort.class);
        when(port.liveDialect()).thenReturn(DatabaseBackupDialect.SQLITE);
        DatabaseDisasterBackupService service = new DatabaseDisasterBackupService(
                publisherHolding(backupSetId, BackupType.DATABASE_DISASTER_BACKUP, "POSTGRESQL", DUMP, true),
                port,
                "1.0.0");
        var requester = com.uxplima.uxmskyblock.core.domain.identity.ProfileId.of(java.util.UUID.randomUUID());
        var issued = (DatabaseDisasterBackupService.RestoreOutcome.CodeIssued)
                service.requestRestore(requester, backupSetId);

        assertThat(service.restoreDatabase(BUCKET, backupSetId, requester, issued.code()))
                .isInstanceOf(DatabaseDisasterBackupService.RestoreOutcome.Refused.class);
        verify(port, never()).restoreDatabaseBackup(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("A set that never finished publishing is refused, marker and all")
    void anunfinishedSetIsRefused() {
        var backupSetId = com.uxplima.uxmskyblock.core.domain.backup.BackupSetId.random();
        DatabaseBackupPort port = mock(DatabaseBackupPort.class);
        when(port.liveDialect()).thenReturn(DatabaseBackupDialect.SQLITE);
        DatabaseDisasterBackupService service = new DatabaseDisasterBackupService(
                publisherHolding(backupSetId, BackupType.DATABASE_DISASTER_BACKUP, "SQLITE", DUMP, false),
                port,
                "1.0.0");
        var requester = com.uxplima.uxmskyblock.core.domain.identity.ProfileId.of(java.util.UUID.randomUUID());
        var issued = (DatabaseDisasterBackupService.RestoreOutcome.CodeIssued)
                service.requestRestore(requester, backupSetId);

        assertThat(service.restoreDatabase(BUCKET, backupSetId, requester, issued.code()))
                .isInstanceOf(DatabaseDisasterBackupService.RestoreOutcome.Refused.class);
    }

    @Test
    @DisplayName("A set that is not a database backup is refused before it is read")
    void anislandSetIsRefused() {
        var backupSetId = com.uxplima.uxmskyblock.core.domain.backup.BackupSetId.random();
        DatabaseBackupPort port = mock(DatabaseBackupPort.class);
        when(port.liveDialect()).thenReturn(DatabaseBackupDialect.SQLITE);
        DatabaseDisasterBackupService service = new DatabaseDisasterBackupService(
                publisherHolding(backupSetId, BackupType.ROOT_BACKUP, "SQLITE", DUMP, true), port, "1.0.0");
        var requester = com.uxplima.uxmskyblock.core.domain.identity.ProfileId.of(java.util.UUID.randomUUID());
        var issued = (DatabaseDisasterBackupService.RestoreOutcome.CodeIssued)
                service.requestRestore(requester, backupSetId);

        assertThat(service.restoreDatabase(BUCKET, backupSetId, requester, issued.code()))
                .isInstanceOf(DatabaseDisasterBackupService.RestoreOutcome.Refused.class);
        verify(port, never()).restoreDatabaseBackup(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("A disaster backup is written apart from the island ones, so neither can hide the other")
    void adisasterBackupHasItsOwnPlace() {
        assertThat(DatabaseDisasterBackupService.prefixFor(
                        com.uxplima.uxmskyblock.core.domain.backup.BackupSetId.random()))
                .startsWith("database/");
    }
}
