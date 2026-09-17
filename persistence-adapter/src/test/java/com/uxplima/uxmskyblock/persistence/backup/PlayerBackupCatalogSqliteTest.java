package com.uxplima.uxmskyblock.persistence.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.backup.BackupCatalogRecord;
import com.uxplima.uxmskyblock.core.domain.backup.BackupLifecycleState;
import com.uxplima.uxmskyblock.core.domain.backup.BackupSetId;
import com.uxplima.uxmskyblock.core.domain.backup.BackupType;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PlayerBackupCatalogSqliteTest {

    private Database database;
    private PlayerBackupCatalogAdapter adapter;

    @BeforeEach
    void setUp() {
        database = DatabaseTestFixture.createSqliteInMemory();
        MigrationRunner runner = new MigrationRunner(database);
        runner.apply(SkyblockMigrations.getMigrations(database.dialect()));
        adapter = new PlayerBackupCatalogAdapter(database);
    }

    @AfterEach
    void tearDown() {
        if (database != null && !database.isClosed()) {
            database.close();
        }
    }

    @Test
    @DisplayName("Non-existent record returns empty optional")
    void nonExistentRecord() {
        assertThat(adapter.findById(BackupSetId.random())).isEmpty();
    }

    @Test
    @DisplayName("Save and retrieve backup catalog record")
    void saveAndRetrieveRecord() {
        BackupSetId id = BackupSetId.random();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        BackupCatalogRecord record = new BackupCatalogRecord(
                id,
                BackupType.ROOT_BACKUP,
                "ISLAND",
                "isl-123",
                BackupLifecycleState.PLANNED,
                1L,
                100L,
                8,
                "1.0.0",
                null,
                now,
                null,
                now);

        adapter.save(record);

        Optional<BackupCatalogRecord> fetched = adapter.findById(id);
        assertThat(fetched).isPresent();
        BackupCatalogRecord actual = fetched.get();
        assertThat(actual.backupSetId()).isEqualTo(id);
        assertThat(actual.backupType()).isEqualTo(BackupType.ROOT_BACKUP);
        assertThat(actual.targetRootTypeId()).isEqualTo("ISLAND");
        assertThat(actual.targetRootKey()).isEqualTo("isl-123");
        assertThat(actual.state()).isEqualTo(BackupLifecycleState.PLANNED);
        assertThat(actual.authorityEpoch()).isEqualTo(1L);
        assertThat(actual.dbVersion()).isEqualTo(100L);
        assertThat(actual.schemaVersion()).isEqualTo(8);
        assertThat(actual.pluginVersion()).isEqualTo("1.0.0");
        assertThat(actual.failureReason()).isNull();
        assertThat(actual.completedAt()).isNull();
    }

    @Test
    @DisplayName("Save updates existing record via upsert")
    void upsertRecord() {
        BackupSetId id = BackupSetId.random();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        BackupCatalogRecord record = new BackupCatalogRecord(
                id,
                BackupType.ROOT_BACKUP,
                "ISLAND",
                "isl-456",
                BackupLifecycleState.UPLOADING,
                2L,
                101L,
                8,
                "1.0.0",
                null,
                now,
                null,
                now);

        adapter.save(record);

        Instant completedAt = now.plusSeconds(10);
        BackupCatalogRecord updatedRecord = new BackupCatalogRecord(
                id,
                BackupType.ROOT_BACKUP,
                "ISLAND",
                "isl-456",
                BackupLifecycleState.AVAILABLE,
                2L,
                101L,
                8,
                "1.0.0",
                null,
                now,
                completedAt,
                completedAt);

        adapter.save(updatedRecord);

        Optional<BackupCatalogRecord> fetched = adapter.findById(id);
        assertThat(fetched).isPresent();
        assertThat(fetched.get().state()).isEqualTo(BackupLifecycleState.AVAILABLE);
        assertThat(fetched.get().completedAt()).isNotNull();
    }

    @Test
    @DisplayName("findByRoot returns all records matching root type and key ordered by created_at DESC")
    void findByRoot() {
        Instant t1 = Instant.now().minusSeconds(100).truncatedTo(ChronoUnit.MILLIS);
        Instant t2 = Instant.now().minusSeconds(50).truncatedTo(ChronoUnit.MILLIS);

        BackupSetId id1 = BackupSetId.random();
        BackupSetId id2 = BackupSetId.random();
        BackupSetId idOther = BackupSetId.random();

        adapter.save(new BackupCatalogRecord(
                id1,
                BackupType.ROOT_BACKUP,
                "ISLAND",
                "isl-target",
                BackupLifecycleState.AVAILABLE,
                1L,
                10L,
                8,
                "1.0.0",
                null,
                t1,
                t1,
                t1));
        adapter.save(new BackupCatalogRecord(
                id2,
                BackupType.ROOT_BACKUP,
                "ISLAND",
                "isl-target",
                BackupLifecycleState.AVAILABLE,
                1L,
                11L,
                8,
                "1.0.0",
                null,
                t2,
                t2,
                t2));
        adapter.save(new BackupCatalogRecord(
                idOther,
                BackupType.ROOT_BACKUP,
                "ISLAND",
                "isl-other",
                BackupLifecycleState.AVAILABLE,
                1L,
                12L,
                8,
                "1.0.0",
                null,
                t2,
                t2,
                t2));

        List<BackupCatalogRecord> results = adapter.findByRoot("ISLAND", "isl-target");
        assertThat(results).hasSize(2);
        assertThat(results.get(0).backupSetId()).isEqualTo(id2);
        assertThat(results.get(1).backupSetId()).isEqualTo(id1);

        assertThat(adapter.findByRoot("ISLAND", "non-existent")).isEmpty();
    }

    @Test
    @DisplayName("updateState transitions state and sets completion timestamp")
    void updateStateTransition() {
        BackupSetId id = BackupSetId.random();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        adapter.save(new BackupCatalogRecord(
                id,
                BackupType.ROOT_BACKUP,
                "ISLAND",
                "isl-state",
                BackupLifecycleState.UPLOADING,
                1L,
                1L,
                8,
                "1.0.0",
                null,
                now,
                null,
                now));

        adapter.updateState(id, BackupLifecycleState.AVAILABLE, null);

        BackupCatalogRecord available = adapter.findById(id).orElseThrow();
        assertThat(available.state()).isEqualTo(BackupLifecycleState.AVAILABLE);
        assertThat(available.completedAt()).isNotNull();

        adapter.updateState(id, BackupLifecycleState.FAILED, "Disk quota exceeded");
        BackupCatalogRecord failed = adapter.findById(id).orElseThrow();
        assertThat(failed.state()).isEqualTo(BackupLifecycleState.FAILED);
        assertThat(failed.failureReason()).isEqualTo("Disk quota exceeded");
    }

    @Test
    @DisplayName("updateState on unknown ID throws BackupCatalogPersistenceException")
    void updateStateNotFound() {
        assertThatThrownBy(() -> adapter.updateState(BackupSetId.random(), BackupLifecycleState.AVAILABLE, null))
                .isInstanceOf(BackupCatalogPersistenceException.class)
                .hasMessageContaining("No backup catalog record found");
    }
}
