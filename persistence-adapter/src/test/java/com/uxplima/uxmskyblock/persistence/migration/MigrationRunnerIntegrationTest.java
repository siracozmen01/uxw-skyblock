package com.uxplima.uxmskyblock.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import com.uxplima.uxmlib.storage.StorageException;
import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * P1-006 integration verification harness testing the real {@link MigrationRunner} from
 * {@code uxmlib-storage} (0.88.0) directly against an isolated SQLite database fixture.
 *
 * <p>Verifies history table initialization, version ordering, current-version tracking,
 * idempotent reruns, incremental application, transactional rollback on failure,
 * duplicate version constraint behavior, and SQL identifier validation.
 */
class MigrationRunnerIntegrationTest {

    @TempDir
    Path tempDir;

    private Database database;
    private MigrationRunner runner;

    // Unmistakably test-only fixtures proving upstream MigrationRunner semantics
    private static final Migration TEST_MIGRATION_V1 = new Migration(1, "create test_fixture_alpha", """
            CREATE TABLE test_fixture_alpha (
                id INTEGER PRIMARY KEY,
                name TEXT NOT NULL
            );
            INSERT INTO test_fixture_alpha (id, name) VALUES (1, 'initial');
            """);

    private static final Migration TEST_MIGRATION_V2 = new Migration(2, "create test_fixture_beta", """
            CREATE TABLE test_fixture_beta (
                id INTEGER PRIMARY KEY,
                value TEXT NOT NULL
            );
            INSERT INTO test_fixture_beta (id, value) VALUES (100, 'beta_val');
            """);

    private static final Migration TEST_MIGRATION_V3 = new Migration(3, "append to test_fixture_alpha", """
            INSERT INTO test_fixture_alpha (id, name) VALUES (2, 'second');
            """);

    private static final Migration FAILING_MIGRATION_V3 = new Migration(3, "failing migration with syntax error", """
            THIS IS INVALID SQL THAT CANNOT EXECUTE;
            """);

    @BeforeEach
    void setUp() {
        Path dbPath = tempDir.resolve("migration-test.db");
        database = Database.builder().sqlite(dbPath).build();
        runner = new MigrationRunner(database);
    }

    @AfterEach
    void tearDown() {
        if (database != null && !database.isClosed()) {
            database.close();
        }
    }

    @Test
    @DisplayName(
            "A. History table initialization: clean database automatically receives upstream history table with correct schema")
    void initializesDefaultHistoryTableAndReportsInitialVersion() throws Exception {
        assertThat(runner.currentVersion()).isEqualTo(0);
        assertThat(runner.historyTable()).isEqualTo(MigrationRunner.DEFAULT_HISTORY_TABLE);

        int applied = runner.apply(List.of(TEST_MIGRATION_V1));
        assertThat(applied).isEqualTo(1);
        assertThat(runner.currentVersion()).isEqualTo(1);

        try (Connection conn = database.connection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(
                        "SELECT version, description, applied_at_ms FROM " + MigrationRunner.DEFAULT_HISTORY_TABLE)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt("version")).isEqualTo(1);
            assertThat(rs.getString("description")).isEqualTo("create test_fixture_alpha");
            assertThat(rs.getLong("applied_at_ms")).isGreaterThan(0L);
            assertThat(rs.next()).isFalse();
        }
    }

    @Test
    @DisplayName("B. Ascending order: migrations provided in unsorted order execute in ascending version order")
    void appliesMigrationsInAscendingOrderRegardlessOfInputOrder() throws Exception {
        // Provide V2 before V1 deliberately
        int applied = runner.apply(List.of(TEST_MIGRATION_V2, TEST_MIGRATION_V1));
        assertThat(applied).isEqualTo(2);

        // Verify both tables were created and populated
        try (Connection conn = database.connection();
                Statement stmt = conn.createStatement()) {
            try (ResultSet rs1 = stmt.executeQuery("SELECT name FROM test_fixture_alpha WHERE id = 1")) {
                assertThat(rs1.next()).isTrue();
                assertThat(rs1.getString("name")).isEqualTo("initial");
            }
            try (ResultSet rs2 = stmt.executeQuery("SELECT value FROM test_fixture_beta WHERE id = 100")) {
                assertThat(rs2.next()).isTrue();
                assertThat(rs2.getString("value")).isEqualTo("beta_val");
            }
        }
        assertThat(runner.currentVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("C. Current version: accurately tracks highest applied migration version")
    void reportsCurrentVersionAccurately() {
        assertThat(runner.currentVersion()).isEqualTo(0);

        runner.apply(List.of(TEST_MIGRATION_V1));
        assertThat(runner.currentVersion()).isEqualTo(1);

        runner.apply(List.of(TEST_MIGRATION_V2));
        assertThat(runner.currentVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("D. Idempotent re-run: re-running identical migrations skips already applied ones")
    void idempotentRerunSkipsAlreadyAppliedMigrations() throws Exception {
        int firstRun = runner.apply(List.of(TEST_MIGRATION_V1, TEST_MIGRATION_V2));
        assertThat(firstRun).isEqualTo(2);

        // Re-run exact same migration set
        int secondRun = runner.apply(List.of(TEST_MIGRATION_V1, TEST_MIGRATION_V2));
        assertThat(secondRun).isEqualTo(0);

        // Verify rows were not duplicated
        try (Connection conn = database.connection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM test_fixture_alpha")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("E. Apply newer only: when V1/V2 exist, only newly introduced V3 is executed")
    void appliesOnlyNewerMigrationsWhenPriorMigrationsExist() throws Exception {
        runner.apply(List.of(TEST_MIGRATION_V1, TEST_MIGRATION_V2));
        assertThat(runner.currentVersion()).isEqualTo(2);

        int applied = runner.apply(List.of(TEST_MIGRATION_V1, TEST_MIGRATION_V2, TEST_MIGRATION_V3));
        assertThat(applied).isEqualTo(1);
        assertThat(runner.currentVersion()).isEqualTo(3);

        try (Connection conn = database.connection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM test_fixture_alpha")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("F. Strictly newer semantics: unapplied migrations with version <= currentVersion are skipped")
    void skipsMigrationsWithVersionNotExceedingCurrentVersion() {
        // Apply V1 and V3 directly, jumping to current version 3
        runner.apply(List.of(TEST_MIGRATION_V1, TEST_MIGRATION_V3));
        assertThat(runner.currentVersion()).isEqualTo(3);

        // Submitting V2 now: upstream compares migration.version() > current (2 > 3 is false)
        int applied = runner.apply(List.of(TEST_MIGRATION_V2));
        assertThat(applied).isEqualTo(0);
        assertThat(runner.currentVersion()).isEqualTo(3);
    }

    @Test
    @DisplayName(
            "G. Failure / Rollback: failing migration surfaces StorageException and rolls back that migration while preserving prior commits")
    void rollsBackFailingMigrationWhilePreservingPriorCommittedState() throws Exception {
        runner.apply(List.of(TEST_MIGRATION_V1, TEST_MIGRATION_V2));
        assertThat(runner.currentVersion()).isEqualTo(2);

        // Execute failing V3
        assertThatThrownBy(() -> runner.apply(List.of(FAILING_MIGRATION_V3)))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("migration failed");

        // Current version remains 2
        assertThat(runner.currentVersion()).isEqualTo(2);

        // Prior committed state remains intact
        try (Connection conn = database.connection();
                Statement stmt = conn.createStatement()) {
            try (ResultSet rs1 = stmt.executeQuery("SELECT COUNT(*) FROM test_fixture_alpha")) {
                assertThat(rs1.next()).isTrue();
                assertThat(rs1.getInt(1)).isEqualTo(1);
            }
            try (ResultSet rs2 = stmt.executeQuery("SELECT COUNT(*) FROM test_fixture_beta")) {
                assertThat(rs2.next()).isTrue();
                assertThat(rs2.getInt(1)).isEqualTo(1);
            }
        }
    }

    @Test
    @DisplayName(
            "H. Duplicate version behavior: duplicate migration versions trigger StorageException via history table PRIMARY KEY constraint")
    void duplicateVersionInSameBatchTriggersPrimaryKeyConstraintViolation() {
        Migration duplicateV1 =
                new Migration(1, "conflicting version 1", "CREATE TABLE test_duplicate (id INTEGER PRIMARY KEY);");

        // Upstream MigrationRunner does not pre-validate duplicate versions in memory;
        // it sorts them and attempts to insert the duplicate version into the history table,
        // which triggers a PRIMARY KEY constraint violation, rolls back, and throws StorageException.
        assertThatThrownBy(() -> runner.apply(List.of(TEST_MIGRATION_V1, duplicateV1)))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("migration failed");

        // The first migration committed, so version is 1
        assertThat(runner.currentVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("I. Parameter validation: null arguments throw NullPointerException")
    @SuppressWarnings("NullAway")
    void rejectsNullArguments() {
        assertThatThrownBy(() -> new MigrationRunner((Database) null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MigrationRunner(database, null)).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> runner.apply(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("J. Custom history table name: upstream MigrationRunner enforces valid SQL identifier")
    void supportsCustomHistoryTableAndEnforcesSqlIdentifier() {
        MigrationRunner customRunner = new MigrationRunner(database, "custom_schema_history");
        assertThat(customRunner.historyTable()).isEqualTo("custom_schema_history");

        assertThatThrownBy(() -> new MigrationRunner(database, "invalid-table;name"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("simple SQL identifier");
    }

    @Test
    @DisplayName("K. Zero production schema verification: no production Skyblock tables exist in test database")
    void provesZeroProductionSchemaCreated() throws Exception {
        runner.apply(List.of(TEST_MIGRATION_V1, TEST_MIGRATION_V2, TEST_MIGRATION_V3));

        List<String> forbiddenProductionTables = List.of(
                "player_accounts",
                "player_profiles",
                "player_sessions",
                "islands",
                "island_members",
                "inventories",
                "outbox",
                "inbox");

        try (Connection conn = database.connection()) {
            for (String table : forbiddenProductionTables) {
                try (ResultSet rs = conn.getMetaData().getTables(null, null, table, null)) {
                    assertThat(rs.next())
                            .as("Production table '%s' must not be created in P1-006", table)
                            .isFalse();
                }
            }
        }
    }
}
