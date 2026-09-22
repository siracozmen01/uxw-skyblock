package com.uxplima.uxmskyblock.core.application.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.backup.BackupCatalogPort;
import com.uxplima.uxmskyblock.core.application.storage.ObjectStoragePort;
import com.uxplima.uxmskyblock.core.domain.backup.BackupArtifact;
import com.uxplima.uxmskyblock.core.domain.backup.BackupManifest;
import com.uxplima.uxmskyblock.core.domain.backup.BackupSetId;
import com.uxplima.uxmskyblock.core.domain.backup.BackupType;
import com.uxplima.uxmskyblock.core.domain.snapshot.RestoreMode;
import com.uxplima.uxmskyblock.core.domain.storage.StorageBucket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A whole database is never put back as a side effect of putting one island back.
 *
 * <p>The specification says so in those words and the guard that enforced it asked the caller a
 * question instead: a database backup set was refused only when disaster recovery was <em>not</em>
 * confirmed. The one caller there is confirms everything, so a backup set holding a whole database
 * dump would have been handed to the island relational restore as though it were one island's
 * rows. Nothing could reach it while a database backup could not be taken at all. One can now.
 */
class AnIslandRestoreIsNeverAWholeDatabaseTest {

    private static final StorageBucket BUCKET = new StorageBucket("backups");

    private static final byte[] DUMP =
            "-- SKYBLOCK_DISASTER_BACKUP_V1:SQLITE".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    /** Storage that really holds the marker and the artifact, so nothing fails for want of them. */
    private static ObjectStoragePort storageHolding(byte[] artifact) {
        ObjectStoragePort storage = mock(ObjectStoragePort.class);
        when(storage.exists(any(), any())).thenReturn(true);
        when(storage.getObject(any(), any())).thenReturn(java.util.Optional.of(artifact));
        return storage;
    }

    private static BackupManifest manifestOf(BackupType type) {
        return new BackupManifest(
                BackupSetId.random(),
                type,
                "ISLAND",
                UUID.randomUUID().toString(),
                Instant.now(),
                1L,
                1L,
                1,
                "1.0.0",
                Map.of(
                        "database.sql",
                        new BackupArtifact(
                                "database.sql",
                                DUMP.length,
                                com.uxplima.uxmskyblock.core.application.backup.BackupService.computeSha256(DUMP))),
                "CONSISTENT");
    }

    @Test
    @DisplayName("A database backup set is refused however loudly the caller confirms")
    void adatabaseSetIsRefusedWhateverTheCallerSays() {
        ObjectStoragePort storage = storageHolding(DUMP);
        RootRelationalSnapshotPort relational = mock(RootRelationalSnapshotPort.class);
        WorldDimensionSnapshotPort world = mock(WorldDimensionSnapshotPort.class);
        IslandRestoreService service =
                new IslandRestoreService(mock(BackupCatalogPort.class), storage, relational, world);

        IslandRestoreService.RestoreOutcome outcome = service.executeRestore(
                manifestOf(BackupType.DATABASE_DISASTER_BACKUP),
                BUCKET,
                "database/whatever",
                true,
                RestoreMode.FULL_ISLAND);

        assertThat(outcome).isInstanceOf(IslandRestoreService.RestoreOutcome.Failure.class);
        verify(relational, never()).restoreRelationalSnapshot(any(), any(), any());
        verify(world, never()).restoreWorldDimension(any(), any(), any());
        verify(storage, never()).getObject(any(), any());
        verify(storage, never()).exists(any(), any());
    }

    @Test
    @DisplayName("An island backup set still gets as far as looking for its marker")
    void anislandSetIsStillRead() {
        ObjectStoragePort storage = mock(ObjectStoragePort.class);
        IslandRestoreService service = new IslandRestoreService(
                mock(BackupCatalogPort.class),
                storage,
                mock(RootRelationalSnapshotPort.class),
                mock(WorldDimensionSnapshotPort.class));

        IslandRestoreService.RestoreOutcome outcome = service.executeRestore(
                manifestOf(BackupType.ROOT_BACKUP), BUCKET, "islands/whatever", true, RestoreMode.FULL_ISLAND);

        assertThat(outcome)
                .describedAs("it fails for want of a marker, which means it got past the boundary guard")
                .isInstanceOf(IslandRestoreService.RestoreOutcome.Failure.class);
        verify(storage).exists(any(), any());
    }
}
