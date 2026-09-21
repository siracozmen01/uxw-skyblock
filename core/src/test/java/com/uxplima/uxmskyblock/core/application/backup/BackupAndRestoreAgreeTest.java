package com.uxplima.uxmskyblock.core.application.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.uxplima.uxmskyblock.core.application.snapshot.IslandRestoreService;
import com.uxplima.uxmskyblock.core.application.snapshot.RootRelationalSnapshotPort;
import com.uxplima.uxmskyblock.core.application.snapshot.WorldDimensionSnapshotPort;
import com.uxplima.uxmskyblock.core.application.storage.ObjectStoragePort;
import com.uxplima.uxmskyblock.core.domain.backup.BackupCatalogRecord;
import com.uxplima.uxmskyblock.core.domain.backup.BackupLifecycleState;
import com.uxplima.uxmskyblock.core.domain.backup.BackupSetId;
import com.uxplima.uxmskyblock.core.domain.dimension.DimensionId;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.snapshot.RestoreMode;
import com.uxplima.uxmskyblock.core.domain.storage.StorageBucket;
import com.uxplima.uxmskyblock.core.domain.storage.StorageObjectMetadata;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The backup and the restore agree, end to end, through real storage.
 *
 * <p>The two halves communicate through filenames. The restore dispatches on the word inside the
 * name, sending anything holding "relational" to the relational port and anything holding "world"
 * to the world port, and a capture that wrote "island.json" would be read and silently skipped:
 * the restore would report success having restored nothing. Nothing checked that they matched,
 * because until now there was no capture side at all.
 *
 * <p>This runs a real {@link BackupService} and a real {@link IslandRestoreService} over an
 * in-memory storage, so the marker ordering, the SHA-256 verification and the filename dispatch are
 * all the production ones.
 */
class BackupAndRestoreAgreeTest {

    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());
    private static final StorageBucket BUCKET = new StorageBucket("the-operators-own-bucket");
    private static final byte[] RELATIONAL_BYTES = "{\"islands\":[\"one\"]}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OVERWORLD_BYTES = {9, 8, 7};
    private static final byte[] NETHER_BYTES = {6, 5};

    private InMemoryStorage storage;
    private FakeBackupCatalog catalog;
    private IslandBackupService backupService;
    private RootRelationalSnapshotPort restoringRelational;
    private WorldDimensionSnapshotPort restoringWorlds;
    private IslandRestoreService restoreService;

    @BeforeEach
    void setUp() {
        storage = new InMemoryStorage();
        catalog = new FakeBackupCatalog();

        RootRelationalSnapshotPort capturingRelational = mock(RootRelationalSnapshotPort.class);
        when(capturingRelational.captureRelationalSnapshot(any(), anyLong())).thenReturn(RELATIONAL_BYTES);
        WorldDimensionSnapshotPort capturingWorlds = mock(WorldDimensionSnapshotPort.class);
        when(capturingWorlds.captureWorldDimension(any(), eq(DimensionId.OVERWORLD)))
                .thenReturn(OVERWORLD_BYTES);
        when(capturingWorlds.captureWorldDimension(any(), eq(DimensionId.THE_NETHER)))
                .thenReturn(NETHER_BYTES);

        backupService = new IslandBackupService(
                new BackupService(catalog, storage), capturingRelational, capturingWorlds, "1.2.3");

        restoringRelational = mock(RootRelationalSnapshotPort.class);
        restoringWorlds = mock(WorldDimensionSnapshotPort.class);
        restoreService = new IslandRestoreService(catalog, storage, restoringRelational, restoringWorlds);
    }

    private BackupSetId backUpOverworldAndNether() {
        IslandBackupService.BackupOutcome outcome =
                backupService.backupIsland(ISLAND, BUCKET, List.of(DimensionId.OVERWORLD, DimensionId.THE_NETHER));
        assertThat(outcome).isInstanceOf(IslandBackupService.BackupOutcome.Success.class);
        return ((IslandBackupService.BackupOutcome.Success) outcome).backupSetId();
    }

    @Test
    @DisplayName("Everything the backup wrote is handed back to the port that owns it")
    void everyArtifactReachesItsOwnPort() {
        BackupSetId id = backUpOverworldAndNether();

        Optional<com.uxplima.uxmskyblock.core.domain.backup.BackupManifest> manifest =
                new BackupService(catalog, storage).loadManifest(BUCKET, IslandBackupService.prefixFor(id));
        assertThat(manifest)
                .describedAs("the manifest is where the restore looks for it")
                .isPresent();

        IslandRestoreService.RestoreOutcome outcome = restoreService.executeRestore(
                manifest.get(), BUCKET, IslandBackupService.prefixFor(id), true, RestoreMode.FULL_ISLAND);

        assertThat(outcome).isInstanceOf(IslandRestoreService.RestoreOutcome.Success.class);
        assertThat(((IslandRestoreService.RestoreOutcome.Success) outcome).artifactsRestored())
                .describedAs("three artifacts were written, so three must be put back")
                .isEqualTo(3);
        verify(restoringRelational).restoreRelationalSnapshot(any(), eq(RELATIONAL_BYTES), eq(RestoreMode.FULL_ISLAND));
        verify(restoringWorlds).restoreWorldDimension(any(), eq(DimensionId.OVERWORLD), eq(OVERWORLD_BYTES));
        verify(restoringWorlds).restoreWorldDimension(any(), eq(DimensionId.THE_NETHER), eq(NETHER_BYTES));
    }

    @Test
    @DisplayName("The restore reads the island back under the key the backup filed it with")
    void theIslandKeySurvivesTheRoundTrip() {
        BackupSetId id = backUpOverworldAndNether();

        List<BackupCatalogRecord> filed =
                catalog.findByRoot("ISLAND", ISLAND.value().toString());
        assertThat(filed).hasSize(1);
        assertThat(filed.get(0).backupSetId()).isEqualTo(id);
        assertThat(filed.get(0).state())
                .describedAs("a rollback only picks a backup that finished")
                .isEqualTo(BackupLifecycleState.AVAILABLE);
    }

    @Test
    @DisplayName("Geometry only puts the world back and leaves the relational rows alone")
    void geometryOnlyLeavesTheRowsAlone() {
        BackupSetId id = backUpOverworldAndNether();
        var manifest = new BackupService(catalog, storage)
                .loadManifest(BUCKET, IslandBackupService.prefixFor(id))
                .orElseThrow();

        restoreService.executeRestore(
                manifest, BUCKET, IslandBackupService.prefixFor(id), true, RestoreMode.GEOMETRY_ONLY);

        verify(restoringWorlds).restoreWorldDimension(any(), eq(DimensionId.OVERWORLD), eq(OVERWORLD_BYTES));
        verify(restoringRelational, org.mockito.Mockito.never()).restoreRelationalSnapshot(any(), any(), any());
    }

    @Test
    @DisplayName("A backup with a byte changed under it is refused, which is what the checksums are for")
    void atamperedArtifactIsRefused() {
        BackupSetId id = backUpOverworldAndNether();
        String prefix = IslandBackupService.prefixFor(id);
        var manifest =
                new BackupService(catalog, storage).loadManifest(BUCKET, prefix).orElseThrow();

        storage.objects.put(prefix + "/" + IslandBackupService.RELATIONAL_ARTIFACT, new byte[] {0});

        IslandRestoreService.RestoreOutcome outcome =
                restoreService.executeRestore(manifest, BUCKET, prefix, true, RestoreMode.FULL_ISLAND);

        assertThat(outcome).isInstanceOf(IslandRestoreService.RestoreOutcome.Failure.class);
        verify(restoringRelational, org.mockito.Mockito.never()).restoreRelationalSnapshot(any(), any(), any());
    }

    @Test
    @DisplayName("The availability marker is written last, so a half uploaded set is never restorable")
    void theMarkerIsWrittenLast() {
        BackupSetId id = backUpOverworldAndNether();
        String markerKey = IslandBackupService.prefixFor(id) + "/" + BackupService.AVAILABILITY_MARKER_FILE_NAME;

        assertThat(storage.putOrder.get(storage.putOrder.size() - 1)).isEqualTo(markerKey);
    }

    private static final class FakeBackupCatalog implements BackupCatalogPort {
        private final Map<BackupSetId, BackupCatalogRecord> records = new ConcurrentHashMap<>();

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
            List<BackupCatalogRecord> found = new ArrayList<>();
            for (BackupCatalogRecord record : records.values()) {
                if (rootTypeId.equals(record.targetRootTypeId()) && rootKey.equals(record.targetRootKey())) {
                    found.add(record);
                }
            }
            return found;
        }

        @Override
        public void updateState(BackupSetId id, BackupLifecycleState state, @Nullable String failureReason) {
            BackupCatalogRecord existing = records.get(id);
            if (existing != null) {
                records.put(id, existing.withState(state, failureReason, Instant.now()));
            }
        }
    }

    private static final class InMemoryStorage implements ObjectStoragePort {
        private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        private final List<String> putOrder = new ArrayList<>();

        @Override
        public void putObject(StorageBucket bucket, String key, byte[] data, StorageObjectMetadata metadata) {
            objects.put(key, data);
            putOrder.add(key);
        }

        @Override
        public Optional<byte[]> getObject(StorageBucket bucket, String key) {
            return Optional.ofNullable(objects.get(key));
        }

        @Override
        public boolean exists(StorageBucket bucket, String key) {
            return objects.containsKey(key);
        }

        @Override
        public void deleteObject(StorageBucket bucket, String key) {
            objects.remove(key);
        }

        @Override
        public List<String> listObjects(StorageBucket bucket, String prefix) {
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
            byte[] data = objects.get(key);
            return data == null
                    ? Optional.empty()
                    : Optional.of(StorageObjectMetadata.of(
                            "application/octet-stream", data.length, BackupService.computeSha256(data), Instant.now()));
        }
    }
}
