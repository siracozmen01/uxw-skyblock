package com.uxplima.uxmskyblock.core.application.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.snapshot.RootRelationalSnapshotPort;
import com.uxplima.uxmskyblock.core.application.snapshot.WorldDimensionSnapshotPort;
import com.uxplima.uxmskyblock.core.domain.backup.BackupArtifact;
import com.uxplima.uxmskyblock.core.domain.backup.BackupCatalogRecord;
import com.uxplima.uxmskyblock.core.domain.backup.BackupManifest;
import com.uxplima.uxmskyblock.core.domain.backup.BackupType;
import com.uxplima.uxmskyblock.core.domain.dimension.DimensionId;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.storage.StorageBucket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Making the backup that {@code /is admin restore} puts back.
 *
 * <p>The whole reading side was here and nothing ever wrote one, so an administrator could restore
 * backups that could not exist. The filenames are what the two halves agree on: the restore
 * dispatches on the word in the name, so a capture that writes "island.json" is a capture the
 * restore reads and silently skips.
 */
class IslandBackupServiceTest {

    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());
    private static final StorageBucket BUCKET = new StorageBucket("the-operators-own-bucket");

    private BackupService backups;
    private RootRelationalSnapshotPort relational;
    private WorldDimensionSnapshotPort worlds;
    private IslandBackupService service;

    @BeforeEach
    void setUp() {
        backups = mock(BackupService.class);
        when(backups.publishBackup(any(), anyString(), any(), any(), any())).thenReturn(true);

        relational = mock(RootRelationalSnapshotPort.class);
        when(relational.captureRelationalSnapshot(any(), anyLong()))
                .thenReturn("{\"islands\":[]}".getBytes(StandardCharsets.UTF_8));

        worlds = mock(WorldDimensionSnapshotPort.class);
        when(worlds.captureWorldDimension(any(), any())).thenReturn(new byte[] {1, 2, 3});

        service = new IslandBackupService(backups, relational, worlds, "1.2.3");
    }

    @SuppressWarnings("unchecked")
    private Map<String, byte[]> publishedPayloads() {
        ArgumentCaptor<Map<String, byte[]>> captor = ArgumentCaptor.forClass(Map.class);
        verify(backups).publishBackup(any(), anyString(), any(), any(), captor.capture());
        return captor.getValue();
    }

    private BackupManifest publishedManifest() {
        ArgumentCaptor<BackupManifest> captor = ArgumentCaptor.forClass(BackupManifest.class);
        verify(backups).publishBackup(any(), anyString(), any(), captor.capture(), any());
        return captor.getValue();
    }

    @Test
    @DisplayName("The relational rows are captured under the name the restore dispatches on")
    void theRelationalArtifactIsNamedTheWayTheRestoreReadsIt() {
        service.backupIsland(ISLAND, BUCKET, List.of());

        assertThat(publishedPayloads()).containsKey(IslandBackupService.RELATIONAL_ARTIFACT);
        assertThat(IslandBackupService.RELATIONAL_ARTIFACT)
                .describedAs("the restore sends anything holding 'relational' to the relational port")
                .contains("relational");
    }

    @Test
    @DisplayName("Every dimension asked for is captured, under a name the restore reads as a world")
    void everyDimensionIsCaptured() {
        service.backupIsland(
                ISLAND, BUCKET, List.of(DimensionId.OVERWORLD, DimensionId.THE_NETHER, DimensionId.THE_END));

        Map<String, byte[]> payloads = publishedPayloads();
        assertThat(payloads).hasSize(4);
        assertThat(payloads.keySet().stream().filter(name -> name.contains("world")))
                .describedAs("the restore sends anything holding 'world' to the world port")
                .hasSize(3);
        verify(worlds).captureWorldDimension(any(), eq(DimensionId.OVERWORLD));
        verify(worlds).captureWorldDimension(any(), eq(DimensionId.THE_NETHER));
        verify(worlds).captureWorldDimension(any(), eq(DimensionId.THE_END));
    }

    @Test
    @DisplayName("A dimension nobody asked for is not captured, so a one world server pays for one")
    void onlyTheDimensionsAskedForAreCaptured() {
        service.backupIsland(ISLAND, BUCKET, List.of(DimensionId.OVERWORLD));

        verify(worlds).captureWorldDimension(any(), eq(DimensionId.OVERWORLD));
        verify(worlds, never()).captureWorldDimension(any(), eq(DimensionId.THE_NETHER));
        verify(worlds, never()).captureWorldDimension(any(), eq(DimensionId.THE_END));
    }

    @Test
    @DisplayName("Every artifact carries the checksum of the bytes actually captured")
    void everyArtifactCarriesItsOwnChecksum() {
        service.backupIsland(ISLAND, BUCKET, List.of(DimensionId.OVERWORLD));

        Map<String, byte[]> payloads = publishedPayloads();
        Map<String, BackupArtifact> artifacts = publishedManifest().artifacts();
        assertThat(artifacts.keySet()).isEqualTo(payloads.keySet());
        for (Map.Entry<String, BackupArtifact> entry : artifacts.entrySet()) {
            byte[] bytes = java.util.Objects.requireNonNull(payloads.get(entry.getKey()));
            assertThat(entry.getValue().sizeBytes()).isEqualTo(bytes.length);
            assertThat(entry.getValue().sha256Checksum())
                    .describedAs("publishBackup refuses a set whose checksums do not match")
                    .isEqualTo(BackupService.computeSha256(bytes));
        }
    }

    @Test
    @DisplayName("The manifest and the catalogue name this island, so a rollback can find it again")
    void theBackupIsFiledUnderThisIsland() {
        service.backupIsland(ISLAND, BUCKET, List.of());

        ArgumentCaptor<BackupCatalogRecord> captor = ArgumentCaptor.forClass(BackupCatalogRecord.class);
        verify(backups).publishBackup(any(), anyString(), captor.capture(), any(), any());
        assertThat(captor.getValue().targetRootTypeId()).isEqualTo("ISLAND");
        assertThat(captor.getValue().targetRootKey()).isEqualTo(ISLAND.value().toString());

        BackupManifest manifest = publishedManifest();
        assertThat(manifest.rootTypeId()).isEqualTo("ISLAND");
        assertThat(manifest.rootKey()).isEqualTo(ISLAND.value().toString());
        assertThat(manifest.backupType()).isEqualTo(BackupType.ROOT_BACKUP);
    }

    @Test
    @DisplayName("The prefix written is the prefix the restore looks under")
    void thePrefixMatchesTheOneTheRestoreReads() {
        IslandBackupService.BackupOutcome outcome = service.backupIsland(ISLAND, BUCKET, List.of());

        assertThat(outcome).isInstanceOf(IslandBackupService.BackupOutcome.Success.class);
        IslandBackupService.BackupOutcome.Success success = (IslandBackupService.BackupOutcome.Success) outcome;
        verify(backups)
                .publishBackup(
                        eq(BUCKET), eq(IslandBackupService.prefixFor(success.backupSetId())), any(), any(), any());
    }

    @Test
    @DisplayName("The plugin's own version is recorded, not one written in the source")
    void thePluginVersionIsRecorded() {
        service.backupIsland(ISLAND, BUCKET, List.of());

        assertThat(publishedManifest().pluginVersion()).isEqualTo("1.2.3");
    }

    @Test
    @DisplayName("A capture that throws publishes nothing rather than half a backup")
    void aFailedCapturePublishesNothing() {
        when(worlds.captureWorldDimension(any(), any())).thenThrow(new IllegalStateException("world unloaded"));

        IslandBackupService.BackupOutcome outcome =
                service.backupIsland(ISLAND, BUCKET, List.of(DimensionId.OVERWORLD));

        assertThat(outcome).isInstanceOf(IslandBackupService.BackupOutcome.Failure.class);
        verify(backups, never()).publishBackup(any(), anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("A refused publication is a failure the caller is told about")
    void aRefusedPublicationIsAFailure() {
        when(backups.publishBackup(any(), anyString(), any(), any(), any())).thenReturn(false);

        assertThat(service.backupIsland(ISLAND, BUCKET, List.of()))
                .isInstanceOf(IslandBackupService.BackupOutcome.Failure.class);
    }

    @Test
    @DisplayName("Two backups of the same island are two sets, so one never writes over the other")
    void twoBackupsAreTwoSets() {
        IslandBackupService.BackupOutcome first = service.backupIsland(ISLAND, BUCKET, List.of());
        IslandBackupService.BackupOutcome second = service.backupIsland(ISLAND, BUCKET, List.of());

        assertThat(((IslandBackupService.BackupOutcome.Success) first).backupSetId())
                .isNotEqualTo(((IslandBackupService.BackupOutcome.Success) second).backupSetId());
    }
}
