package com.uxplima.uxmskyblock.core.application.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.Map;

import com.uxplima.uxmskyblock.core.application.backup.BackupCatalogPort;
import com.uxplima.uxmskyblock.core.application.backup.BackupService;
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
 * Putting one island back can never become putting the whole database back.
 *
 * <p>The game mode architecture names this test. A whole database dump handed to the island restore,
 * under every mode and even with the disaster flag set, is refused before storage, the island's rows
 * or its world are read: the whole database has its own service and its own confirmation code.
 */
class RootRollbackCannotTriggerFullDatabaseRestoreTest {

    @Test
    @DisplayName("An island restore handed a whole database backup refuses it and touches nothing")
    void anIslandRestoreRefusesADatabaseDump() {
        BackupCatalogPort catalog = mock(BackupCatalogPort.class);
        ObjectStoragePort storage = mock(ObjectStoragePort.class);
        RootRelationalSnapshotPort rows = mock(RootRelationalSnapshotPort.class);
        WorldDimensionSnapshotPort world = mock(WorldDimensionSnapshotPort.class);
        IslandRestoreService restore = new IslandRestoreService(catalog, storage, rows, world);
        byte[] dump = "-- whole database".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        BackupManifest wholeDatabase = new BackupManifest(
                BackupSetId.random(),
                BackupType.DATABASE_DISASTER_BACKUP,
                "DATABASE",
                "SQLITE",
                Instant.now(),
                1L,
                1L,
                1,
                "1.0.0",
                Map.of(
                        "database.dump",
                        new BackupArtifact("database.dump", dump.length, BackupService.computeSha256(dump))),
                "CONSISTENT");

        for (boolean confirmed : new boolean[] {false, true}) {
            for (RestoreMode mode : RestoreMode.values()) {
                assertThat(restore.executeRestore(
                                wholeDatabase, new StorageBucket("backups"), "backups/any", confirmed, mode))
                        .describedAs("mode %s, disaster flag %s", mode, confirmed)
                        .isInstanceOf(IslandRestoreService.RestoreOutcome.Failure.class);
            }
        }
        verifyNoInteractions(catalog, storage, rows, world);
    }
}
