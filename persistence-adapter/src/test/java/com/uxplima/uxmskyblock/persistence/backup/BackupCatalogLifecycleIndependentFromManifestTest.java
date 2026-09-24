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
 * The catalogue row moves through a backup's life, and the manifest never does.
 *
 * <p>The testing standard names this test. {@code backup_operations} in SQL is where a backup is
 * staged, uploading, available or being deleted; {@code manifest.json} is written once and says what
 * the backup is. A row moved through every state it takes, including a deletion cut short, leaves the
 * manifest on the destination byte for byte as it was published, with no state in it.
 */
class BackupCatalogLifecycleIndependentFromManifestTest {

    private static final StorageBucket BUCKET = StorageBucket.DEFAULT_BACKUPS;

    @TempDir
    Path dir;

    @Test
    @DisplayName("Moving the catalogue row through staged, uploading and deleting leaves the manifest untouched")
    void theRowMovesAndTheManifestDoesNot() {
        Database database = DatabaseTestFixture.createSqliteInMemory();
        new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
        PlayerBackupCatalogAdapter catalog = new PlayerBackupCatalogAdapter(database);
        Failing destination = new Failing(new LocalFilesystemStorageAdapter(dir.resolve("store")));
        BackupService service = new BackupService(catalog, List.of(destination));

        byte[] world = "world".getBytes(StandardCharsets.UTF_8);
        BackupSetId setId = BackupSetId.random();
        BackupManifest manifest = new BackupManifest(
                setId,
                BackupType.ROOT_BACKUP,
                "uxm:island",
                "isl-9",
                Instant.parse("2026-09-24T10:00:00Z"),
                1L,
                10L,
                30,
                "0.1.0",
                Map.of("world.bin", new BackupArtifact("world.bin", world.length, BackupService.computeSha256(world))),
                "CONSISTENT");
        BackupCatalogRecord record = new BackupCatalogRecord(
                setId,
                BackupType.ROOT_BACKUP,
                "uxm:island",
                "isl-9",
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
        String manifestKey = prefix + "/" + BackupService.MANIFEST_FILE_NAME;

        assertThat(service.publishBackup(BUCKET, prefix, record, manifest, Map.of("world.bin", world)))
                .isTrue();
        byte[] published = destination.getObject(BUCKET, manifestKey).orElseThrow();
        String text = new String(published, StandardCharsets.UTF_8);
        for (BackupLifecycleState state : BackupLifecycleState.values()) {
            assertThat(text)
                    .describedAs("the manifest holds no lifecycle state")
                    .doesNotContain(state.name());
        }

        for (BackupLifecycleState state : List.of(BackupLifecycleState.STAGED, BackupLifecycleState.UPLOADING)) {
            catalog.updateState(setId, state, null);
            assertThat(catalog.findById(setId).orElseThrow().state()).isEqualTo(state);
            assertThat(destination.getObject(BUCKET, manifestKey).orElseThrow()).isEqualTo(published);
        }

        destination.failingDeletes = true;
        assertThat(service.deleteBackup(BUCKET, prefix, setId, manifest)).isFalse();
        assertThat(catalog.findById(setId).orElseThrow().state())
                .describedAs("a deletion cut short, held back from restore by the row alone")
                .isEqualTo(BackupLifecycleState.RECOVERY_REQUIRED);
        assertThat(destination.getObject(BUCKET, manifestKey).orElseThrow())
                .describedAs("the manifest the half deleted backup still carries")
                .isEqualTo(published);
        database.close();
    }

    /** A destination that refuses every write while it is failing, the way a store that is down does. */
    private static final class Failing implements ObjectStoragePort {
        private final ObjectStoragePort store;
        private boolean failing;
        private boolean failingDeletes;

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
            // Refuses artifacts only, so a deletion gets past the marker and stops.
            if (failingDeletes && objectKey.endsWith(".bin")) {
                throw new IllegalStateException("the store is down");
            }
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
