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
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A snapshot cut off before it was published does not exist to discovery or to a restore by id.
 *
 * <p>The testing standard names this test. The chunks go up, and the node stops before the manifest,
 * or before the availability marker that is published after it. Discovery offers nothing, and a
 * restore asked for by the snapshot's id finds no manifest to restore from: it used to read one
 * wherever it lay, marker or not, and would have restored a publication that stopped short.
 * Clearing the orphaned chunks is the bucket's lifecycle rule, which is the operator's storage.
 */
class ObjectStoreSnapshotPublishCrashTest {

    private static final StorageBucket BUCKET = StorageBucket.DEFAULT_BACKUPS;

    @TempDir
    Path dir;

    @ParameterizedTest(name = "stopped before {0}")
    @ValueSource(strings = {BackupService.MANIFEST_FILE_NAME, BackupService.AVAILABILITY_MARKER_FILE_NAME})
    void aSnapshotStoppedShortDoesNotExist(String stoppedBefore) {
        Database database = DatabaseTestFixture.createSqliteInMemory();
        new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
        Crashing store = new Crashing(new LocalFilesystemStorageAdapter(dir.resolve("s3")), stoppedBefore);
        BackupService service = new BackupService(new PlayerBackupCatalogAdapter(database), List.of(store));

        byte[] chunk = "chunk".getBytes(StandardCharsets.UTF_8);
        BackupSetId setId = BackupSetId.random();
        BackupManifest manifest = new BackupManifest(
                setId,
                BackupType.ROOT_BACKUP,
                "uxm:island",
                "isl-3",
                Instant.parse("2026-09-24T10:00:00Z"),
                1L,
                10L,
                30,
                "0.1.0",
                Map.of(
                        "chunks/r.0.0.mca",
                        new BackupArtifact("chunks/r.0.0.mca", chunk.length, BackupService.computeSha256(chunk))),
                "CONSISTENT");
        BackupCatalogRecord record = new BackupCatalogRecord(
                setId,
                BackupType.ROOT_BACKUP,
                "uxm:island",
                "isl-3",
                BackupLifecycleState.PLANNED,
                1L,
                10L,
                30,
                "0.1.0",
                null,
                Instant.parse("2026-09-24T10:00:00Z"),
                null,
                Instant.parse("2026-09-24T10:00:00Z"));
        String prefix = "backups/" + setId;

        assertThat(service.publishBackup(BUCKET, prefix, record, manifest, Map.of("chunks/r.0.0.mca", chunk)))
                .isFalse();
        assertThat(store.exists(BUCKET, prefix + "/chunks/r.0.0.mca"))
                .describedAs("the chunk went up")
                .isTrue();

        assertThat(service.discoverBackupsWithoutDatabase(store, BUCKET, "backups"))
                .describedAs("available backups")
                .isEmpty();
        assertThat(service.loadManifest(BUCKET, prefix))
                .describedAs("the manifest a restore by id reads")
                .isEmpty();
        database.close();
    }

    /** A store the node stops writing to just before the named object goes up. */
    private static final class Crashing implements ObjectStoragePort {
        private final ObjectStoragePort store;
        private final String stopBefore;

        Crashing(ObjectStoragePort store, String stopBefore) {
            this.store = store;
            this.stopBefore = stopBefore;
        }

        @Override
        public void putObject(StorageBucket bucket, String objectKey, byte[] data, StorageObjectMetadata metadata) {
            if (objectKey.endsWith(stopBefore)) {
                throw new IllegalStateException("the node stopped");
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
