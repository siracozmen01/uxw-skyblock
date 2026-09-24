package com.uxplima.uxmskyblock.persistence.backup;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.backup.BackupService;
import com.uxplima.uxmskyblock.core.application.storage.ObjectStoragePort;
import com.uxplima.uxmskyblock.core.domain.backup.BackupArtifact;
import com.uxplima.uxmskyblock.core.domain.backup.BackupCatalogRecord;
import com.uxplima.uxmskyblock.core.domain.backup.BackupLifecycleState;
import com.uxplima.uxmskyblock.core.domain.backup.BackupManifest;
import com.uxplima.uxmskyblock.core.domain.backup.BackupSetId;
import com.uxplima.uxmskyblock.core.domain.backup.BackupType;
import com.uxplima.uxmskyblock.core.domain.storage.StorageBucket;
import com.uxplima.uxmskyblock.core.domain.storage.StorageObjectMetadata;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.storage.LocalFilesystemStorageAdapter;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A backup publication run again after it was cut short ends as one backup, identical everywhere.
 *
 * <p>The testing standard names this test. The first run stops on the second destination; the
 * operator runs it again, and then once more. The SQL catalogue holds one record for the set, each
 * destination holds the same manifest, byte for byte, and each offers the backup once.
 */
class BackupSetPublicationIdempotencyTest {

    private static final StorageBucket BUCKET = StorageBucket.DEFAULT_BACKUPS;

    @TempDir
    Path dir;

    @Test
    @DisplayName("A cut publication run again, and again, leaves one record and one identical manifest")
    void aRetriedPublicationIsOneBackup() {
        Database database = DatabaseTestFixture.createSqliteInMemory();
        new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
        PlayerBackupCatalogAdapter catalog = new PlayerBackupCatalogAdapter(database);
        LocalFilesystemStorageAdapter first = new LocalFilesystemStorageAdapter(dir.resolve("first"));
        Failing second = new Failing(new LocalFilesystemStorageAdapter(dir.resolve("second")));
        BackupService service = new BackupService(catalog, List.of(first, second));

        byte[] world = "world".getBytes(StandardCharsets.UTF_8);
        byte[] rows = "rows".getBytes(StandardCharsets.UTF_8);
        BackupSetId setId = BackupSetId.random();
        BackupManifest manifest = new BackupManifest(
                setId,
                BackupType.ROOT_BACKUP,
                "uxm:island",
                "isl-7",
                Instant.parse("2026-09-24T10:00:00Z"),
                1L,
                10L,
                30,
                "0.1.0",
                Map.of(
                        "world.bin", new BackupArtifact("world.bin", world.length, BackupService.computeSha256(world)),
                        "database.sql",
                                new BackupArtifact("database.sql", rows.length, BackupService.computeSha256(rows))),
                "CONSISTENT");
        BackupCatalogRecord record = new BackupCatalogRecord(
                setId,
                BackupType.ROOT_BACKUP,
                "uxm:island",
                "isl-7",
                BackupLifecycleState.PLANNED,
                1L,
                10L,
                30,
                "0.1.0",
                null,
                Instant.parse("2026-09-24T10:00:00Z"),
                null,
                Instant.parse("2026-09-24T10:00:00Z"));
        Map<String, byte[]> payloads = Map.of("world.bin", world, "database.sql", rows);
        String prefix = "backups/" + setId;
        String manifestKey = prefix + "/" + BackupService.MANIFEST_FILE_NAME;

        second.failing = true;
        assertThat(service.publishBackup(BUCKET, prefix, record, manifest, payloads))
                .isFalse();
        byte[] firstRun = first.getObject(BUCKET, manifestKey).orElseThrow();

        second.failing = false;
        assertThat(service.publishBackup(BUCKET, prefix, record, manifest, payloads))
                .isTrue();
        assertThat(service.publishBackup(BUCKET, prefix, record, manifest, payloads))
                .isTrue();

        assertThat(catalog.findByRoot("uxm:island", "isl-7")).singleElement().satisfies(held -> {
            assertThat(held.backupSetId()).isEqualTo(setId);
            assertThat(held.state()).isEqualTo(BackupLifecycleState.AVAILABLE);
        });
        assertThat(first.getObject(BUCKET, manifestKey).orElseThrow()).isEqualTo(firstRun);
        assertThat(second.getObject(BUCKET, manifestKey).orElseThrow()).isEqualTo(firstRun);
        for (ObjectStoragePort destination : List.of(first, second)) {
            assertThat(service.discoverBackupsWithoutDatabase(destination, BUCKET, "backups"))
                    .extracting(BackupManifest::backupSetId)
                    .containsExactly(setId);
        }
        database.close();
    }

    /** A destination that refuses every write while it is failing, the way a store that is down does. */
    private static final class Failing implements ObjectStoragePort {
        private final ObjectStoragePort store;
        private boolean failing;

        Failing(ObjectStoragePort store) {
            this.store = store;
        }

        @Override
        public void putObject(StorageBucket bucket, String objectKey, byte[] data, StorageObjectMetadata metadata) {
            if (failing) {
                throw new IllegalStateException("the store is down");
            }
            store.putObject(bucket, objectKey, data, metadata);
        }

        @Override
        public Optional<byte[]> getObject(StorageBucket bucket, String objectKey) {
            return store.getObject(bucket, objectKey);
        }

        @Override
        public boolean exists(StorageBucket bucket, String objectKey) {
            return store.exists(bucket, objectKey);
        }

        @Override
        public void deleteObject(StorageBucket bucket, String objectKey) {
            store.deleteObject(bucket, objectKey);
        }

        @Override
        public List<String> listObjects(StorageBucket bucket, String prefix) {
            return store.listObjects(bucket, prefix);
        }

        @Override
        public Optional<StorageObjectMetadata> getMetadata(StorageBucket bucket, String objectKey) {
            return store.getMetadata(bucket, objectKey);
        }
    }
}
