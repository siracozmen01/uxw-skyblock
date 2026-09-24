package com.uxplima.uxmskyblock.core.application.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.uxplima.uxmskyblock.core.application.storage.MirroredObjectStorage;
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

/**
 * A backup written to a mirror is still there when one of its destinations is not.
 *
 * <p>The persistence specification publishes a {@code MIRRORED} storage policy: a backup counts as
 * available only once every destination holds it, and each destination keeps its own copy. Only one
 * destination could be configured, so a server that lost its bucket lost every backup in it.
 */
class AMirroredBackupSurvivesALostDestinationTest {

    private static final StorageBucket BUCKET = StorageBucket.DEFAULT_BACKUPS;

    private final Store local = new Store();
    private final Store remote = new Store();
    private final Catalog catalog = new Catalog();
    private final MirroredObjectStorage mirror = new MirroredObjectStorage(List.of(local, remote));

    private BackupSetId setId = BackupSetId.random();
    private String prefix = "";
    private Map<String, byte[]> payloads = Map.of();
    private BackupManifest manifest = manifest(BackupSetId.random(), new byte[0]);

    @BeforeEach
    void setUp() {
        setId = BackupSetId.random();
        prefix = "backups/" + setId;
        byte[] world = "world-chunks".getBytes(StandardCharsets.UTF_8);
        payloads = Map.of("world.bin", world);
        manifest = manifest(setId, world);
    }

    @Test
    @DisplayName("A backup published to a mirror is available once both destinations hold it")
    void bothDestinationsHoldIt() {
        assertThat(publish()).isTrue();

        assertThat(catalog.state(setId)).isEqualTo(BackupLifecycleState.AVAILABLE);
        for (Store destination : List.of(local, remote)) {
            assertThat(destination.objects).containsKeys(marker(), prefix + "/world.bin", manifestKey());
        }
    }

    @Test
    @DisplayName("A backup whose remote copy is lost is read whole from the local one, and the other way round")
    void eitherCopyIsEnough() {
        publish();

        remote.objects.clear();
        assertThat(mirror.exists(BUCKET, marker())).isTrue();
        assertThat(mirror.getObject(BUCKET, prefix + "/world.bin")).hasValue(payloads.get("world.bin"));

        publish();
        local.objects.clear();
        assertThat(mirror.exists(BUCKET, marker())).isTrue();
        assertThat(mirror.getObject(BUCKET, manifestKey())).isPresent();
    }

    @Test
    @DisplayName("A destination that refuses the publication leaves the backup partial, and the other still holds it")
    void aRefusedDestinationLeavesItPartial() {
        remote.refusing = true;

        assertThat(publish()).isFalse();

        assertThat(catalog.state(setId)).isEqualTo(BackupLifecycleState.PARTIAL);
        assertThat(local.objects).containsKey(marker());
        assertThat(remote.objects).doesNotContainKey(marker());
    }

    @Test
    @DisplayName("A database backup one destination refused is reported as partial, not as never made")
    void aRefusedDestinationIsReportedPartial() {
        remote.refusing = true;
        DatabaseBackupPort port = org.mockito.Mockito.mock(DatabaseBackupPort.class);
        org.mockito.Mockito.when(port.liveDialect())
                .thenReturn(com.uxplima.uxmskyblock.core.domain.backup.DatabaseBackupDialect.SQLITE);
        org.mockito.Mockito.when(port.captureDatabaseBackup(org.mockito.ArgumentMatchers.any()))
                .thenReturn("-- dump".getBytes(StandardCharsets.UTF_8));
        BackupService service = new BackupService(catalog, MirroredObjectStorage.destinationsOf(mirror));

        assertThat(new DatabaseDisasterBackupService(service, port, "0.1.0").backupDatabase(BUCKET))
                .isInstanceOf(DatabaseDisasterBackupService.Outcome.Partial.class);
        assertThat(local.objects.keySet()).anyMatch(key -> key.endsWith(BackupService.AVAILABILITY_MARKER_FILE_NAME));
    }

    @Test
    @DisplayName("A write one destination refuses still reaches the others, and is reported as refused")
    void aRefusedWriteReachesTheRest() {
        local.refusing = true;
        byte[] data = "x".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> mirror.putObject(
                        BUCKET, "k", data, StorageObjectMetadata.of("text/plain", 1, "h", Instant.now())))
                .isInstanceOf(IllegalStateException.class);
        assertThat(remote.objects).containsKey("k");
    }

    @Test
    @DisplayName("A destination that cannot be reached reads as one that has nothing, so the other answers")
    void anUnreachableDestinationIsPassedOver() {
        publish();
        local.unreachable = true;

        assertThat(mirror.getObject(BUCKET, prefix + "/world.bin")).hasValue(payloads.get("world.bin"));
        assertThat(mirror.listObjects(BUCKET, prefix)).contains(marker());
    }

    @Test
    @DisplayName("A single destination is published to on its own, and a mirror as each of its destinations")
    void theServiceSeesEveryDestination() {
        assertThat(MirroredObjectStorage.destinationsOf(mirror)).containsExactly(local, remote);
        assertThat(MirroredObjectStorage.destinationsOf(local)).containsExactly(local);
    }

    private boolean publish() {
        BackupService service = new BackupService(catalog, MirroredObjectStorage.destinationsOf(mirror));
        return service.publishBackup(BUCKET, prefix, record(setId), manifest, payloads);
    }

    private String marker() {
        return prefix + "/" + BackupService.AVAILABILITY_MARKER_FILE_NAME;
    }

    private String manifestKey() {
        return prefix + "/" + BackupService.MANIFEST_FILE_NAME;
    }

    private static BackupManifest manifest(BackupSetId id, byte[] world) {
        return new BackupManifest(
                id,
                BackupType.ROOT_BACKUP,
                "uxm:island",
                "isl-1",
                Instant.parse("2026-09-24T12:00:00Z"),
                1L,
                1L,
                31,
                "0.1.0",
                Map.of("world.bin", new BackupArtifact("world.bin", world.length, BackupService.computeSha256(world))),
                "CONSISTENT");
    }

    private static BackupCatalogRecord record(BackupSetId id) {
        return new BackupCatalogRecord(
                id,
                BackupType.ROOT_BACKUP,
                "uxm:island",
                "isl-1",
                BackupLifecycleState.PLANNED,
                1L,
                1L,
                31,
                "0.1.0",
                null,
                Instant.now(),
                null,
                Instant.now());
    }

    /** One destination: a map, which can be told to refuse writes or to be out of reach. */
    private static final class Store implements ObjectStoragePort {
        final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        boolean refusing;
        boolean unreachable;

        private void reach() {
            if (unreachable) {
                throw new IllegalStateException("destination out of reach");
            }
        }

        @Override
        public void putObject(StorageBucket bucket, String key, byte[] data, StorageObjectMetadata metadata) {
            reach();
            if (refusing) {
                throw new IllegalStateException("destination refused the write");
            }
            objects.put(key, data);
        }

        @Override
        public Optional<byte[]> getObject(StorageBucket bucket, String key) {
            reach();
            return Optional.ofNullable(objects.get(key));
        }

        @Override
        public boolean exists(StorageBucket bucket, String key) {
            reach();
            return objects.containsKey(key);
        }

        @Override
        public void deleteObject(StorageBucket bucket, String key) {
            reach();
            objects.remove(key);
        }

        @Override
        public List<String> listObjects(StorageBucket bucket, String prefix) {
            reach();
            List<String> found = new ArrayList<>();
            for (String key : objects.keySet()) {
                if (key.startsWith(prefix)) {
                    found.add(key);
                }
            }
            return found;
        }

        @Override
        public Optional<StorageObjectMetadata> getMetadata(StorageBucket bucket, String key) {
            reach();
            byte[] data = objects.get(key);
            return data == null
                    ? Optional.empty()
                    : Optional.of(StorageObjectMetadata.of(
                            "application/octet-stream", data.length, BackupService.computeSha256(data), Instant.now()));
        }
    }

    private static final class Catalog implements BackupCatalogPort {
        private final Map<BackupSetId, BackupCatalogRecord> records = new HashMap<>();

        BackupLifecycleState state(BackupSetId id) {
            BackupCatalogRecord found = records.get(id);
            return found == null ? BackupLifecycleState.PLANNED : found.state();
        }

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
            return List.copyOf(records.values());
        }

        @Override
        public void updateState(BackupSetId id, BackupLifecycleState state, @Nullable String failureReason) {
            BackupCatalogRecord existing = records.get(id);
            if (existing != null) {
                records.put(id, existing.withState(state, failureReason, Instant.now()));
            }
        }
    }
}
