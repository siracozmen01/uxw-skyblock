package com.uxplima.uxmskyblock.core.application.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import com.uxplima.uxmskyblock.core.application.snapshot.RootRelationalSnapshotPort;
import com.uxplima.uxmskyblock.core.application.snapshot.WorldDimensionSnapshotPort;
import com.uxplima.uxmskyblock.core.application.storage.ObjectStoragePort;
import com.uxplima.uxmskyblock.core.domain.backup.BackupCatalogRecord;
import com.uxplima.uxmskyblock.core.domain.backup.BackupLifecycleState;
import com.uxplima.uxmskyblock.core.domain.backup.BackupSetId;
import com.uxplima.uxmskyblock.core.domain.dimension.DimensionId;
import com.uxplima.uxmskyblock.core.domain.gamemode.PrimaryGameplayRootRef;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.snapshot.RestoreMode;
import com.uxplima.uxmskyblock.core.domain.storage.StorageBucket;
import com.uxplima.uxmskyblock.core.domain.storage.StorageObjectMetadata;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A backup holds its island still for a bounded window while it reads it, and that bound is the
 * operator's, not a latency figure written into the code.
 *
 * <p>The testing standard names this test. A capture read an island's rows and then its chunks while
 * players kept building, and the manifest called the result consistent. The island is now quiesced for
 * the capture: its protection refuses every change until the capture ends or its deadline passes,
 * whichever is first, and the manifest says {@code QUIESCED}. How long a capture takes is measured, not
 * asserted: no p99 or millisecond ceiling is a rule anywhere in the backup path.
 */
class BackupQuiesceHasNoUnverifiedNumericSloTest {

    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());
    private static final StorageBucket BUCKET = new StorageBucket("backups");

    @Test
    @DisplayName("The island is held still while its rows and its world are read, and let go after")
    void theCaptureRunsInsideTheWindow() {
        CaptureQuiesce quiesce = new CaptureQuiesce();
        List<Boolean> heldDuringCapture = new ArrayList<>();
        FakeBackupCatalog catalog = new FakeBackupCatalog();
        InMemoryStorage storage = new InMemoryStorage();
        IslandBackupService backups = new IslandBackupService(
                new BackupService(catalog, storage),
                relational(() -> heldDuringCapture.add(quiesce.isQuiesced(ISLAND))),
                world(() -> heldDuringCapture.add(quiesce.isQuiesced(ISLAND))),
                "1.0.0");
        backups.quiesceWith(quiesce, Duration.ofSeconds(30));

        IslandBackupService.BackupOutcome outcome =
                backups.backupIsland(ISLAND, BUCKET, List.of(DimensionId.OVERWORLD));

        assertThat(outcome).isInstanceOf(IslandBackupService.BackupOutcome.Success.class);
        assertThat(heldDuringCapture).containsExactly(true, true);
        assertThat(quiesce.isQuiesced(ISLAND))
                .describedAs("let go once the capture is done")
                .isFalse();
        BackupSetId id = ((IslandBackupService.BackupOutcome.Success) outcome).backupSetId();
        assertThat(new BackupService(catalog, storage)
                        .loadManifest(BUCKET, IslandBackupService.prefixFor(id))
                        .orElseThrow()
                        .consistencyResult())
                .isEqualTo("QUIESCED");
    }

    @Test
    @DisplayName("A capture that fails lets the island go")
    void aFailedCaptureLetsGo() {
        CaptureQuiesce quiesce = new CaptureQuiesce();
        IslandBackupService backups = new IslandBackupService(
                new BackupService(new FakeBackupCatalog(), new InMemoryStorage()),
                relational(() -> {
                    throw new IllegalStateException("the database went away");
                }),
                world(() -> {}),
                "1.0.0");
        backups.quiesceWith(quiesce, Duration.ofSeconds(30));

        assertThat(backups.backupIsland(ISLAND, BUCKET, List.of(DimensionId.OVERWORLD)))
                .isInstanceOf(IslandBackupService.BackupOutcome.Failure.class);
        assertThat(quiesce.isQuiesced(ISLAND)).isFalse();
    }

    @Test
    @DisplayName("A window nobody closes ends at its bound, so a hung capture never keeps an island closed")
    void theWindowEndsAtItsBound() {
        Instant[] now = {Instant.parse("2026-09-29T08:00:00Z")};
        CaptureQuiesce quiesce = new CaptureQuiesce(new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now[0];
            }
        });

        quiesce.enter(ISLAND, Duration.ofSeconds(30));
        now[0] = now[0].plusSeconds(29);
        assertThat(quiesce.isQuiesced(ISLAND)).isTrue();
        now[0] = now[0].plusSeconds(1);
        assertThat(quiesce.isQuiesced(ISLAND)).isFalse();
        assertThatThrownBy(() -> quiesce.enter(ISLAND, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("The bound is the capture deadline the operator sets, and no latency figure is a rule")
    void noNumericSloIsEnforced() throws IOException {
        String adminWiring = Files.readString(
                Path.of("../bukkit-adapter/src/main/java/com/uxplima/uxmskyblock/bukkit/bootstrap/AdminWiring.java"));
        assertThat(adminWiring)
                .contains("java.time.Duration captureTimeout = captureTimeoutOf(config.rootNode());")
                .contains("this.islandBackupService.quiesceWith(quiesce, captureTimeout);");
        for (Path root : List.of(
                Path.of("src/main/java/com/uxplima/uxmskyblock/core/application/backup"),
                Path.of("src/main/java/com/uxplima/uxmskyblock/core/application/snapshot"),
                Path.of("../bukkit-adapter/src/main/java/com/uxplima/uxmskyblock/bukkit/snapshot"))) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file :
                        files.filter(f -> f.toString().endsWith(".java")).toList()) {
                    assertThat(Files.readString(file))
                            .describedAs("%s holds a latency figure as a rule", file)
                            .doesNotContainIgnoringCase("p99")
                            .doesNotContain("ofMillis(250)");
                }
            }
        }
    }

    private static RootRelationalSnapshotPort relational(Runnable duringCapture) {
        return new RootRelationalSnapshotPort() {
            @Override
            public byte[] captureRelationalSnapshot(PrimaryGameplayRootRef rootRef, long revision) {
                duringCapture.run();
                return new byte[] {1};
            }

            @Override
            public void restoreRelationalSnapshot(PrimaryGameplayRootRef rootRef, byte[] payload, RestoreMode mode) {}
        };
    }

    private static WorldDimensionSnapshotPort world(Runnable duringCapture) {
        return new WorldDimensionSnapshotPort() {
            @Override
            public byte[] captureWorldDimension(PrimaryGameplayRootRef rootRef, DimensionId dimension) {
                duringCapture.run();
                return new byte[] {2};
            }

            @Override
            public void restoreWorldDimension(PrimaryGameplayRootRef rootRef, DimensionId dimension, byte[] payload) {}
        };
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
