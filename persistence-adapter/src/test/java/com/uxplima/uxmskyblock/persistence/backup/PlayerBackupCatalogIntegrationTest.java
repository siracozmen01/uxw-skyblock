package com.uxplima.uxmskyblock.persistence.backup;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;

@Tag("database-integration")
@Execution(ExecutionMode.SAME_THREAD)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PlayerBackupCatalogIntegrationTest {

    private static MariaDBContainer<?> mariaDbContainer;
    private static PostgreSQLContainer<?> postgresContainer;

    private static Database mariaDatabase;
    private static Database postgresDatabase;

    private static PlayerBackupCatalogAdapter mariaAdapter;
    private static PlayerBackupCatalogAdapter postgresAdapter;

    @BeforeAll
    static void setUpAll() {
        mariaDbContainer = DatabaseTestFixture.newMariaDbContainer();
        mariaDbContainer.start();
        mariaDatabase =
                DatabaseTestFixture.connectToContainer(mariaDbContainer, com.uxplima.uxmlib.storage.sql.Dialect.MYSQL);
        new MigrationRunner(mariaDatabase).apply(SkyblockMigrations.getMigrations(mariaDatabase.dialect()));
        mariaAdapter = new PlayerBackupCatalogAdapter(mariaDatabase);

        postgresContainer = DatabaseTestFixture.newPostgresContainer();
        postgresContainer.start();
        postgresDatabase = DatabaseTestFixture.connectToContainer(
                postgresContainer, com.uxplima.uxmlib.storage.sql.Dialect.POSTGRES);
        new MigrationRunner(postgresDatabase).apply(SkyblockMigrations.getMigrations(postgresDatabase.dialect()));
        postgresAdapter = new PlayerBackupCatalogAdapter(postgresDatabase);
    }

    @AfterAll
    static void tearDownAll() {
        if (mariaDatabase != null && !mariaDatabase.isClosed()) {
            mariaDatabase.close();
        }
        if (mariaDbContainer != null) {
            mariaDbContainer.stop();
        }
        if (postgresDatabase != null && !postgresDatabase.isClosed()) {
            postgresDatabase.close();
        }
        if (postgresContainer != null) {
            postgresContainer.stop();
        }
    }

    @Test
    @Order(1)
    @DisplayName("MariaDB: backup catalog lifecycle and dialect-specific upsert")
    void mariaDbBackupCatalogLifecycle() {
        testBackupCatalogLifecycle(mariaAdapter);
    }

    @Test
    @Order(2)
    @DisplayName("PostgreSQL: backup catalog lifecycle and dialect-specific upsert")
    void postgresBackupCatalogLifecycle() {
        testBackupCatalogLifecycle(postgresAdapter);
    }

    private void testBackupCatalogLifecycle(PlayerBackupCatalogAdapter adapter) {
        BackupSetId id = BackupSetId.random();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        // 1. Save new record
        BackupCatalogRecord record = new BackupCatalogRecord(
                id,
                BackupType.ROOT_BACKUP,
                "ISLAND",
                "isl-int-1",
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

        // 2. Fetch and assert
        Optional<BackupCatalogRecord> fetched = adapter.findById(id);
        assertThat(fetched).isPresent();
        assertThat(fetched.get().state()).isEqualTo(BackupLifecycleState.PLANNED);
        assertThat(fetched.get().targetRootKey()).isEqualTo("isl-int-1");

        // 3. Upsert update
        Instant completedAt = now.plusSeconds(5);
        BackupCatalogRecord updatedRecord = new BackupCatalogRecord(
                id,
                BackupType.ROOT_BACKUP,
                "ISLAND",
                "isl-int-1",
                BackupLifecycleState.AVAILABLE,
                1L,
                100L,
                8,
                "1.0.0",
                null,
                now,
                completedAt,
                completedAt);
        adapter.save(updatedRecord);

        Optional<BackupCatalogRecord> fetchedUpdated = adapter.findById(id);
        assertThat(fetchedUpdated).isPresent();
        assertThat(fetchedUpdated.get().state()).isEqualTo(BackupLifecycleState.AVAILABLE);
        assertThat(fetchedUpdated.get().completedAt()).isNotNull();

        // 4. Update state directly
        adapter.updateState(id, BackupLifecycleState.FAILED, "Integration test simulated failure");
        Optional<BackupCatalogRecord> failed = adapter.findById(id);
        assertThat(failed).isPresent();
        assertThat(failed.get().state()).isEqualTo(BackupLifecycleState.FAILED);
        assertThat(failed.get().failureReason()).isEqualTo("Integration test simulated failure");

        // 5. Query by root
        List<BackupCatalogRecord> byRoot = adapter.findByRoot("ISLAND", "isl-int-1");
        assertThat(byRoot).hasSize(1);
        assertThat(byRoot.get(0).backupSetId()).isEqualTo(id);
    }
}
