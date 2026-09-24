package com.uxplima.uxmskyblock.persistence.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.backup.BackupCatalogPort;
import com.uxplima.uxmskyblock.core.application.backup.BackupService;
import com.uxplima.uxmskyblock.core.application.snapshot.IslandRestoreService;
import com.uxplima.uxmskyblock.core.application.snapshot.RootRelationalSnapshotPort;
import com.uxplima.uxmskyblock.core.application.snapshot.WorldDimensionSnapshotPort;
import com.uxplima.uxmskyblock.core.application.storage.ObjectStoragePort;
import com.uxplima.uxmskyblock.core.domain.backup.BackupArtifact;
import com.uxplima.uxmskyblock.core.domain.backup.BackupManifest;
import com.uxplima.uxmskyblock.core.domain.backup.BackupSetId;
import com.uxplima.uxmskyblock.core.domain.backup.BackupType;
import com.uxplima.uxmskyblock.core.domain.storage.StorageBucket;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A restore interrupted between putting a unit back and writing that down puts it back again.
 *
 * <p>The testing standard names this test. Unit 5 is put back into the world and the host goes down
 * before {@code restore_unit_progress} says so. On the next start the recovery worker finds unit 5 in
 * {@code APPLY_INTENT}, puts it back again by replacing what is there, and carries on to unit 6.
 * Before the tables existed a stopped restore left no trace at all, and the island stayed half put back.
 */
class RestoreCrashAfterUnitApplyBeforeProgressCommitTest {

    private static final StorageBucket BUCKET = new StorageBucket("backups");
    private static final int UNITS = 7;

    private Database database;
    private SqlRestoreProgressAdapter progress;
    private final BackupSetId setId = BackupSetId.random();
    private final String prefix = "backups/" + setId;
    private final Map<String, byte[]> artifacts = new HashMap<>();

    /** The host going down: not an exception the restore could catch and write down. */
    private static final class HostWentDown extends Error {
        private static final long serialVersionUID = 1L;

        HostWentDown() {
            super("the host went down");
        }
    }

    @BeforeEach
    void setUp() {
        database = DatabaseTestFixture.createSqliteInMemory();
        new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
        progress = new SqlRestoreProgressAdapter(database.dataSource());
        for (int unit = 1; unit <= UNITS; unit++) {
            artifacts.put(unitName(unit), ("chunk-section-" + unit).getBytes(StandardCharsets.UTF_8));
        }
    }

    @AfterEach
    void tearDown() {
        if (database != null && !database.isClosed()) {
            database.close();
        }
    }

    @Test
    @DisplayName(
            "Unit 5 put back before a crash is found in APPLY_INTENT, put back again, and the restore goes on to unit 6")
    void unitFiveIsReplayed() throws Exception {
        List<String> firstRun = new ArrayList<>();
        IslandRestoreService crashing = service(firstRun, unitName(5));

        assertThatThrownBy(() -> crashing.executeRestore(manifest(), BUCKET, prefix, true))
                .isInstanceOf(HostWentDown.class);
        assertThat(firstRun).containsExactly(bytes(1), bytes(2), bytes(3), bytes(4), bytes(5));
        assertThat(unitStates())
                .containsEntry(unitName(4), "VERIFIED")
                .containsEntry(unitName(5), "APPLY_INTENT")
                .doesNotContainKey(unitName(6));
        assertThat(progress.unfinished()).hasSize(1);

        // The next start: a new service over the same tables, as a rebooted node has.
        List<String> afterReboot = new ArrayList<>();
        List<IslandRestoreService.RestoreOutcome> resumed =
                service(afterReboot, null).resumeUnfinished(BUCKET, (bucket, at) -> Optional.of(manifest()));

        assertThat(resumed).singleElement().isInstanceOf(IslandRestoreService.RestoreOutcome.Success.class);
        assertThat(afterReboot)
                .describedAs("unit 5 again, by replacement, then on from unit 6; units 1 to 4 are not repeated")
                .containsExactly(bytes(5), bytes(6), bytes(7));
        assertThat(unitStates())
                .hasSize(UNITS)
                .allSatisfy((unit, state) -> assertThat(state).isEqualTo("VERIFIED"));
        assertThat(operationState()).isEqualTo("COMMITTED");
        assertThat(progress.unfinished()).isEmpty();
    }

    @Test
    @DisplayName("A unit that throws, rather than a host that stops, fails the restore and leaves nothing to resume")
    void aThrownUnitIsNotResumed() throws Exception {
        WorldDimensionSnapshotPort world = mock(WorldDimensionSnapshotPort.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("the region would not load"))
                .when(world)
                .restoreWorldDimension(any(), any(), any());
        IslandRestoreService failing = new IslandRestoreService(
                mock(BackupCatalogPort.class), storage(), mock(RootRelationalSnapshotPort.class), world);
        failing.recordProgressIn(progress);

        assertThatThrownBy(() -> failing.executeRestore(manifest(), BUCKET, prefix, true))
                .isInstanceOf(IllegalStateException.class);

        assertThat(operationState()).isEqualTo("FAILED");
        assertThat(progress.unfinished()).isEmpty();
    }

    private IslandRestoreService service(List<String> applied, @Nullable String crashAfter) {
        WorldDimensionSnapshotPort world = mock(WorldDimensionSnapshotPort.class);
        org.mockito.Mockito.doAnswer(invocation -> {
                    String data = new String(invocation.getArgument(2, byte[].class), StandardCharsets.UTF_8);
                    applied.add(data);
                    if (crashAfter != null
                            && data.equals(new String(
                                    java.util.Objects.requireNonNull(artifacts.get(crashAfter)),
                                    StandardCharsets.UTF_8))) {
                        throw new HostWentDown();
                    }
                    return null;
                })
                .when(world)
                .restoreWorldDimension(any(), any(), any());
        IslandRestoreService service = new IslandRestoreService(
                mock(BackupCatalogPort.class), storage(), mock(RootRelationalSnapshotPort.class), world);
        service.recordProgressIn(progress);
        return service;
    }

    private ObjectStoragePort storage() {
        ObjectStoragePort storage = mock(ObjectStoragePort.class);
        when(storage.exists(any(), anyString())).thenReturn(true);
        when(storage.getObject(any(), anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(1, String.class);
            return Optional.ofNullable(artifacts.get(key.substring(key.lastIndexOf('/') + 1)));
        });
        return storage;
    }

    private BackupManifest manifest() {
        Map<String, BackupArtifact> entries = new HashMap<>();
        artifacts.forEach((name, data) ->
                entries.put(name, new BackupArtifact(name, data.length, BackupService.computeSha256(data))));
        return new BackupManifest(
                setId,
                BackupType.ROOT_BACKUP,
                "ISLAND",
                UUID.randomUUID().toString(),
                Instant.parse("2026-09-24T12:00:00Z"),
                1L,
                1L,
                SkyblockMigrations.LATEST_VERSION,
                "0.1.0",
                entries,
                "CONSISTENT");
    }

    private static String unitName(int unit) {
        return "world_overworld_" + String.format("%02d", unit) + ".dat";
    }

    private static String bytes(int unit) {
        return "chunk-section-" + unit;
    }

    private Map<String, String> unitStates() throws Exception {
        Map<String, String> states = new HashMap<>();
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT unit_id, state FROM restore_unit_progress");
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                states.put(rows.getString(1), rows.getString(2));
            }
        }
        return states;
    }

    private String operationState() throws Exception {
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement("SELECT state FROM restore_operations");
                ResultSet rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }
}
