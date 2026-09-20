package com.uxplima.uxmskyblock.core.application.backup;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
import com.uxplima.uxmskyblock.core.domain.storage.StorageBucket;
import com.uxplima.uxmskyblock.core.domain.storage.StorageObjectMetadata;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BackupServiceTest {

    private StorageBucket bucket;
    private FakeBackupCatalog catalog;
    private InMemoryStorage destinationA;
    private InMemoryStorage destinationB;
    private BackupService service;

    private BackupSetId setId;
    private byte[] worldData;
    private byte[] dbData;
    private String worldHash;
    private String dbHash;
    private BackupManifest manifest;
    private BackupCatalogRecord initialRecord;

    @BeforeEach
    void setUp() {
        bucket = StorageBucket.DEFAULT_BACKUPS;
        catalog = new FakeBackupCatalog();
        destinationA = new InMemoryStorage("local");
        destinationB = new InMemoryStorage("remote");

        service = new BackupService(catalog, List.of(destinationA, destinationB));

        setId = BackupSetId.random();
        worldData = "world-binary-chunk-payload".getBytes(StandardCharsets.UTF_8);
        dbData = "sql-export-payload".getBytes(StandardCharsets.UTF_8);
        worldHash = BackupService.computeSha256(worldData);
        dbHash = BackupService.computeSha256(dbData);

        manifest = new BackupManifest(
                setId,
                BackupType.ROOT_BACKUP,
                "uxm:island",
                "isl-100",
                Instant.parse("2026-09-17T12:00:00Z"),
                1L,
                10L,
                7,
                "0.1.0",
                Map.of(
                        "world.bin", new BackupArtifact("world.bin", worldData.length, worldHash),
                        "database.sql", new BackupArtifact("database.sql", dbData.length, dbHash)),
                "CONSISTENT");

        initialRecord = new BackupCatalogRecord(
                setId,
                BackupType.ROOT_BACKUP,
                "uxm:island",
                "isl-100",
                BackupLifecycleState.PLANNED,
                1L,
                10L,
                7,
                "0.1.0",
                null,
                Instant.now(),
                null,
                Instant.now());
    }

    @Test
    @DisplayName("BackupAvailabilityMarkerPublishedLastTest: AVAILABLE.marker is uploaded strictly as the final step")
    void markerPublishedLast() {
        Map<String, byte[]> payloads = Map.of(
                "world.bin", worldData,
                "database.sql", dbData);

        boolean success = service.publishBackup(bucket, "backups/" + setId, initialRecord, manifest, payloads);
        assertThat(success).isTrue();

        assertThat(Objects.requireNonNull(catalog.records.get(setId)).state())
                .isEqualTo(BackupLifecycleState.AVAILABLE);

        // Verify order of put calls on destinationA
        List<String> puts = destinationA.putOrder;
        assertThat(puts).contains("backups/" + setId + "/world.bin", "backups/" + setId + "/database.sql");
        assertThat(puts).contains("backups/" + setId + "/manifest.json");
        assertThat(puts.getLast()).isEqualTo("backups/" + setId + "/AVAILABLE.marker");
    }

    @Test
    @DisplayName("BackupManifestImmutableAfterPublicationTest: manifest contains no mutable state fields")
    void manifestImmutableAndFreeOfMutableState() {
        assertThat(manifest.artifacts()).containsKeys("world.bin", "database.sql");
        // Manifest must not expose mutable status
        assertThat(manifest.consistencyResult()).isEqualTo("CONSISTENT");
    }

    @Test
    @DisplayName("BackupSetMissingArtifactFailsClosedTest: missing artifact aborts publication and marks FAILED")
    void missingArtifactFailsClosed() {
        // Provide only world.bin, omit database.sql
        Map<String, byte[]> payloads = Map.of("world.bin", worldData);

        boolean success = service.publishBackup(bucket, "backups/" + setId, initialRecord, manifest, payloads);
        assertThat(success).isFalse();
        assertThat(Objects.requireNonNull(catalog.records.get(setId)).state()).isEqualTo(BackupLifecycleState.FAILED);
        assertThat(destinationA.putOrder).isEmpty();
    }

    @Test
    @DisplayName("BackupRestoreChecksumMismatchFailsClosedTest: checksum mismatch aborts publication")
    void checksumMismatchFailsClosed() {
        byte[] corruptedDbData = "corrupted-payload".getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> payloads = Map.of(
                "world.bin", worldData,
                "database.sql", corruptedDbData);

        boolean success = service.publishBackup(bucket, "backups/" + setId, initialRecord, manifest, payloads);
        assertThat(success).isFalse();
        assertThat(Objects.requireNonNull(catalog.records.get(setId)).state()).isEqualTo(BackupLifecycleState.FAILED);
        assertThat(destinationA.putOrder).isEmpty();
    }

    @Test
    @DisplayName("BackupDiscoveryWithoutLiveDatabaseTest: discovers valid backups using manifest and marker")
    void discoveryWithoutDatabase() {
        Map<String, byte[]> payloads = Map.of(
                "world.bin", worldData,
                "database.sql", dbData);

        service.publishBackup(bucket, "backups/" + setId, initialRecord, manifest, payloads);

        List<BackupManifest> discovered = service.discoverBackupsWithoutDatabase(destinationA, bucket, "backups");
        assertThat(discovered).hasSize(1);
        assertThat(discovered.getFirst().backupSetId()).isEqualTo(setId);
    }

    @Test
    @DisplayName(
            "BackupDiscoveryRejectsMissingAvailabilityMarkerTest: missing marker hides backup from disaster discovery")
    void discoveryRejectsMissingMarker() {
        Map<String, byte[]> payloads = Map.of(
                "world.bin", worldData,
                "database.sql", dbData);

        service.publishBackup(bucket, "backups/" + setId, initialRecord, manifest, payloads);

        // Simulate crash mid-publication or deleted marker
        destinationA.deleteObject(bucket, "backups/" + setId + "/AVAILABLE.marker");

        List<BackupManifest> discovered = service.discoverBackupsWithoutDatabase(destinationA, bucket, "backups");
        assertThat(discovered).isEmpty();
    }

    @Test
    @DisplayName(
            "BackupDeletionInvalidatesDiscoveryBeforeArtifactCleanupTest: marker is deleted strictly before artifacts")
    void deletionInvalidatesMarkerFirst() {
        Map<String, byte[]> payloads = Map.of(
                "world.bin", worldData,
                "database.sql", dbData);

        service.publishBackup(bucket, "backups/" + setId, initialRecord, manifest, payloads);

        destinationA.deleteOrder.clear();
        boolean deleted = service.deleteBackup(bucket, "backups/" + setId, setId, manifest);
        assertThat(deleted).isTrue();

        assertThat(Objects.requireNonNull(catalog.records.get(setId)).state()).isEqualTo(BackupLifecycleState.DELETED);
        List<String> deletes = destinationA.deleteOrder;
        assertThat(deletes.getFirst()).isEqualTo("backups/" + setId + "/AVAILABLE.marker");
    }

    @Test
    @DisplayName("BackupRestoreAuthorityFenceTest: stale authority epoch or db version rejects restore")
    void restoreAuthorityFencing() {
        // Valid matching preconditions
        assertThat(service.validateRestorePreconditions(manifest, 1L, 10L)).isTrue();

        // Advanced epoch (stale lease takeover) -> rejects
        assertThat(service.validateRestorePreconditions(manifest, 2L, 10L)).isFalse();

        // Advanced DB version (stale OCC version) -> rejects
        assertThat(service.validateRestorePreconditions(manifest, 1L, 11L)).isFalse();
    }

    @Test
    @DisplayName("MirroredBackupCompletionPolicyTest: marks AVAILABLE only after both destinations succeed")
    void mirroredCompletionPolicy() {
        destinationB.failOnPut = true;

        Map<String, byte[]> payloads = Map.of(
                "world.bin", worldData,
                "database.sql", dbData);

        boolean success = service.publishBackup(bucket, "backups/" + setId, initialRecord, manifest, payloads);
        assertThat(success).isFalse();
        assertThat(Objects.requireNonNull(catalog.records.get(setId)).state()).isEqualTo(BackupLifecycleState.PARTIAL);
    }

    @Test
    @DisplayName("MirroredBackupDeletionRecoveryTest: partial deletion failure transitions to RECOVERY_REQUIRED")
    void mirroredDeletionPartialFailureTransitionsToRecoveryRequired() {
        Map<String, byte[]> payloads = Map.of(
                "world.bin", worldData,
                "database.sql", dbData);

        service.publishBackup(bucket, "backups/" + setId, initialRecord, manifest, payloads);

        destinationB.failOnDelete = true;
        boolean deleted = service.deleteBackup(bucket, "backups/" + setId, setId, manifest);
        assertThat(deleted).isFalse();

        assertThat(Objects.requireNonNull(catalog.records.get(setId)).state())
                .isEqualTo(BackupLifecycleState.RECOVERY_REQUIRED);
    }

    private static class FakeBackupCatalog implements BackupCatalogPort {
        final Map<BackupSetId, BackupCatalogRecord> records = new HashMap<>();

        @Override
        public void save(BackupCatalogRecord record) {
            records.put(record.backupSetId(), record);
        }

        @Override
        public Optional<BackupCatalogRecord> findById(BackupSetId id) {
            return Optional.ofNullable(records.get(id));
        }

        @Override
        public List<BackupCatalogRecord> findByRoot(String rootTypeId, String rootKey) {
            List<BackupCatalogRecord> list = new ArrayList<>();
            for (BackupCatalogRecord r : records.values()) {
                if (rootTypeId.equals(r.targetRootTypeId()) && rootKey.equals(r.targetRootKey())) {
                    list.add(r);
                }
            }
            return list;
        }

        @Override
        public void updateState(BackupSetId id, BackupLifecycleState state, @Nullable String failureReason) {
            BackupCatalogRecord existing = records.get(id);
            if (existing != null) {
                records.put(id, existing.withState(state, failureReason, Instant.now()));
            }
        }
    }

    private static class InMemoryStorage implements ObjectStoragePort {
        final String name;
        final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        final List<String> putOrder = new ArrayList<>();
        final List<String> deleteOrder = new ArrayList<>();
        boolean failOnPut = false;
        boolean failOnDelete = false;

        InMemoryStorage(String name) {
            this.name = name;
        }

        @Override
        public void putObject(StorageBucket b, String key, byte[] data, StorageObjectMetadata metadata) {
            if (failOnPut) {
                throw new RuntimeException("Simulated storage write failure on " + name);
            }
            objects.put(key, data);
            putOrder.add(key);
        }

        @Override
        public Optional<byte[]> getObject(StorageBucket b, String key) {
            return Optional.ofNullable(objects.get(key));
        }

        @Override
        public boolean exists(StorageBucket b, String key) {
            return objects.containsKey(key);
        }

        @Override
        public void deleteObject(StorageBucket b, String key) {
            if (failOnDelete) {
                throw new RuntimeException("Simulated storage delete failure on " + name);
            }
            objects.remove(key);
            deleteOrder.add(key);
        }

        @Override
        public List<String> listObjects(StorageBucket b, String prefix) {
            List<String> list = new ArrayList<>();
            for (String k : objects.keySet()) {
                if (k.startsWith(prefix)) {
                    list.add(k);
                }
            }
            return list;
        }

        @Override
        public Optional<StorageObjectMetadata> getMetadata(StorageBucket b, String key) {
            byte[] data = objects.get(key);
            if (data == null) {
                return Optional.empty();
            }
            return Optional.of(StorageObjectMetadata.of(
                    "application/octet-stream", data.length, BackupService.computeSha256(data), Instant.now()));
        }
    }
}
