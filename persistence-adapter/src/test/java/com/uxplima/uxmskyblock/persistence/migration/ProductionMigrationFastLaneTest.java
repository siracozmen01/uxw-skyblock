package com.uxplima.uxmskyblock.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fast-lane SQLite test suite verifying the production persistence foundation (WP2-001).
 *
 * <p>Validates {@link SkyblockMigrations} and upstream {@link MigrationRunner} against in-memory
 * and file-backed SQLite instances without requiring external containers.
 */
class ProductionMigrationFastLaneTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("1. Clean in-memory SQLite database migrates successfully to LATEST_VERSION")
    void cleanDatabaseMigratesSuccessfully() {
        try (Database db = DatabaseTestFixture.createSqliteInMemory()) {
            MigrationRunner runner = new MigrationRunner(db);
            assertThat(runner.currentVersion()).isEqualTo(0);

            int applied = runner.apply(SkyblockMigrations.getMigrations(db.dialect()));
            assertThat(applied).isEqualTo(1);
            assertThat(runner.currentVersion()).isEqualTo(SkyblockMigrations.LATEST_VERSION);
        }
    }

    @Test
    @DisplayName("2. Idempotent migration rerun applies zero migrations")
    void idempotentRerunAppliesZeroMigrations() {
        try (Database db = DatabaseTestFixture.createSqliteInMemory()) {
            MigrationRunner runner = new MigrationRunner(db);
            runner.apply(SkyblockMigrations.getMigrations(db.dialect()));
            assertThat(runner.currentVersion()).isEqualTo(SkyblockMigrations.LATEST_VERSION);

            int secondRun = runner.apply(SkyblockMigrations.getMigrations(db.dialect()));
            assertThat(secondRun).isEqualTo(0);
            assertThat(runner.currentVersion()).isEqualTo(SkyblockMigrations.LATEST_VERSION);
        }
    }

    @Test
    @DisplayName("3. Verified tables exist and deferred/future tables are absent")
    void verifiesTableExistenceAndDeferredAbsence() throws Exception {
        try (Database db = DatabaseTestFixture.createSqliteInMemory()) {
            MigrationRunner runner = new MigrationRunner(db);
            runner.apply(SkyblockMigrations.getMigrations(db.dialect()));

            try (Connection conn = db.connection()) {
                DatabaseMetaData meta = conn.getMetaData();
                Set<String> tables = new HashSet<>();
                try (ResultSet rs = meta.getTables(null, null, "%", new String[] {"TABLE"})) {
                    while (rs.next()) {
                        tables.add(rs.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
                    }
                }

                // Canonical tables that MUST exist
                assertThat(tables)
                        .contains("player_accounts", "player_profiles", "player_sessions", "uxmlib_schema_history");

                // Future / deferred tables that MUST NOT exist in WP2-001
                assertThat(tables)
                        .doesNotContain(
                                "islands",
                                "island_members",
                                "island_locations",
                                "profile_inventories",
                                "inventory_mutation_journals",
                                "profile_switch_operations",
                                "outbox_events",
                                "inbox_events");
            }
        }
    }

    @Test
    @DisplayName("4. Verified columns and types exist on canonical foundation tables")
    void verifiesColumnDefinitions() throws Exception {
        try (Database db = DatabaseTestFixture.createSqliteInMemory()) {
            MigrationRunner runner = new MigrationRunner(db);
            runner.apply(SkyblockMigrations.getMigrations(db.dialect()));

            try (Connection conn = db.connection()) {
                DatabaseMetaData meta = conn.getMetaData();

                // player_accounts columns
                Set<String> accountCols = getColumnNames(meta, "player_accounts");
                assertThat(accountCols)
                        .containsExactlyInAnyOrder(
                                "player_uuid",
                                "active_profile_id",
                                "active_switch_operation_id",
                                "created_at",
                                "updated_at");

                // player_profiles columns
                Set<String> profileCols = getColumnNames(meta, "player_profiles");
                assertThat(profileCols)
                        .containsExactlyInAnyOrder("profile_id", "player_uuid", "profile_type", "created_at");

                // player_sessions columns
                Set<String> sessionCols = getColumnNames(meta, "player_sessions");
                assertThat(sessionCols)
                        .containsExactlyInAnyOrder(
                                "player_uuid",
                                "active_profile_id",
                                "authoritative_node",
                                "session_epoch",
                                "state",
                                "handoff_id",
                                "handoff_target_node",
                                "handoff_expires_at",
                                "last_durable_inventory_version",
                                "lease_expires_at",
                                "updated_at");
            }
        }
    }

    @Test
    @DisplayName("5. Non-circular initial profile creation lifecycle succeeds")
    void nonCircularProfileCreationLifecycleSucceeds() throws Exception {
        try (Database db = DatabaseTestFixture.createSqliteInMemory()) {
            MigrationRunner runner = new MigrationRunner(db);
            runner.apply(SkyblockMigrations.getMigrations(db.dialect()));

            try (Connection conn = db.connection()) {
                enableForeignKeys(conn);

                // 1. Create account with NULL active_profile_id
                try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO player_accounts (player_uuid, active_profile_id) VALUES (?, NULL)")) {
                    stmt.setString(1, "p-uuid-1");
                    stmt.executeUpdate();
                }

                // 2. Create profile for account
                try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO player_profiles (profile_id, player_uuid, profile_type) VALUES (?, ?, 'CLASSIC')")) {
                    stmt.setString(1, "prof-uuid-1");
                    stmt.setString(2, "p-uuid-1");
                    stmt.executeUpdate();
                }

                // 3. Update account active_profile_id to newly created profile
                try (PreparedStatement stmt = conn.prepareStatement(
                        "UPDATE player_accounts SET active_profile_id = ? WHERE player_uuid = ?")) {
                    stmt.setString(1, "prof-uuid-1");
                    stmt.setString(2, "p-uuid-1");
                    stmt.executeUpdate();
                }

                // 4. Create player session
                try (PreparedStatement stmt = conn.prepareStatement("""
                        INSERT INTO player_sessions (
                            player_uuid, active_profile_id, authoritative_node, session_epoch,
                            state, last_durable_inventory_version, lease_expires_at
                        ) VALUES (?, ?, ?, 1, 'ACTIVE', 1, CURRENT_TIMESTAMP)
                        """)) {
                    stmt.setString(1, "p-uuid-1");
                    stmt.setString(2, "prof-uuid-1");
                    stmt.setString(3, "node-alpha");
                    stmt.executeUpdate();
                }

                // Verify session read
                try (PreparedStatement stmt = conn.prepareStatement(
                        "SELECT authoritative_node, session_epoch, state, last_durable_inventory_version FROM player_sessions WHERE player_uuid = ?")) {
                    stmt.setString(1, "p-uuid-1");
                    try (ResultSet rs = stmt.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getString("authoritative_node")).isEqualTo("node-alpha");
                        assertThat(rs.getLong("session_epoch")).isEqualTo(1L);
                        assertThat(rs.getString("state")).isEqualTo("ACTIVE");
                        assertThat(rs.getLong("last_durable_inventory_version")).isEqualTo(1L);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("6. Composite foreign key prevents cross-tenant profile hijacking")
    void compositeForeignKeyPreventsCrossTenantHijacking() throws Exception {
        try (Database db = DatabaseTestFixture.createSqliteInMemory()) {
            MigrationRunner runner = new MigrationRunner(db);
            runner.apply(SkyblockMigrations.getMigrations(db.dialect()));

            try (Connection conn = db.connection()) {
                enableForeignKeys(conn);

                // Player 1 & Profile 1
                execute(conn, "INSERT INTO player_accounts (player_uuid) VALUES ('p-1')");
                execute(conn, "INSERT INTO player_profiles (profile_id, player_uuid) VALUES ('prof-1', 'p-1')");
                execute(conn, "UPDATE player_accounts SET active_profile_id = 'prof-1' WHERE player_uuid = 'p-1'");

                // Player 2 & Profile 2
                execute(conn, "INSERT INTO player_accounts (player_uuid) VALUES ('p-2')");
                execute(conn, "INSERT INTO player_profiles (profile_id, player_uuid) VALUES ('prof-2', 'p-2')");
                execute(conn, "UPDATE player_accounts SET active_profile_id = 'prof-2' WHERE player_uuid = 'p-2'");

                // ATTEMPT 1: Player 1 tries to set active_profile_id to Player 2's profile
                assertThatThrownBy(() -> {
                            try (PreparedStatement stmt = conn.prepareStatement(
                                    "UPDATE player_accounts SET active_profile_id = 'prof-2' WHERE player_uuid = 'p-1'")) {
                                stmt.executeUpdate();
                            }
                        })
                        .isInstanceOf(SQLException.class);

                // ATTEMPT 2: Player 1 tries to open a session referencing Player 2's profile
                assertThatThrownBy(() -> {
                            try (PreparedStatement stmt = conn.prepareStatement("""
                            INSERT INTO player_sessions (
                                player_uuid, active_profile_id, authoritative_node, session_epoch,
                                state, last_durable_inventory_version, lease_expires_at
                            ) VALUES ('p-1', 'prof-2', 'node-bad', 1, 'ACTIVE', 1, CURRENT_TIMESTAMP)
                            """)) {
                                stmt.executeUpdate();
                            }
                        })
                        .isInstanceOf(SQLException.class);
            }
        }
    }

    @Test
    @DisplayName("7. Cascade delete removes associated profiles and sessions")
    void cascadeDeleteCleansUpProfilesAndSessions() throws Exception {
        try (Database db = DatabaseTestFixture.createSqliteInMemory()) {
            MigrationRunner runner = new MigrationRunner(db);
            runner.apply(SkyblockMigrations.getMigrations(db.dialect()));

            try (Connection conn = db.connection()) {
                enableForeignKeys(conn);

                execute(conn, "INSERT INTO player_accounts (player_uuid) VALUES ('p-casc')");
                execute(conn, "INSERT INTO player_profiles (profile_id, player_uuid) VALUES ('prof-casc', 'p-casc')");
                execute(
                        conn,
                        "UPDATE player_accounts SET active_profile_id = 'prof-casc' WHERE player_uuid = 'p-casc'");
                execute(conn, """
                        INSERT INTO player_sessions (
                            player_uuid, active_profile_id, authoritative_node, session_epoch,
                            state, last_durable_inventory_version, lease_expires_at
                        ) VALUES ('p-casc', 'prof-casc', 'node-1', 1, 'ACTIVE', 1, CURRENT_TIMESTAMP)
                        """);

                // Break circular pointer first so account delete is not blocked by fk_player_accounts_active_profile
                execute(conn, "UPDATE player_accounts SET active_profile_id = NULL WHERE player_uuid = 'p-casc'");

                // Delete account -> cascades to player_profiles and player_sessions
                execute(conn, "DELETE FROM player_accounts WHERE player_uuid = 'p-casc'");

                assertThat(queryCount(conn, "SELECT COUNT(*) FROM player_profiles WHERE player_uuid = 'p-casc'"))
                        .isEqualTo(0);
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM player_sessions WHERE player_uuid = 'p-casc'"))
                        .isEqualTo(0);
            }
        }
    }

    @Test
    @DisplayName("8. File-backed SQLite database preserves schema across restarts")
    void fileBackedDatabasePreservesSchemaAcrossRestarts() {
        Path dbFile = tempDir.resolve("skyblock-prod.db");

        // First run: apply migrations
        try (Database db = DatabaseTestFixture.createSqliteFile(dbFile)) {
            MigrationRunner runner = new MigrationRunner(db);
            int applied = runner.apply(SkyblockMigrations.getMigrations(db.dialect()));
            assertThat(applied).isEqualTo(1);
            assertThat(runner.currentVersion()).isEqualTo(1);
        }

        // Second run: reopen existing database file
        try (Database db = DatabaseTestFixture.createSqliteFile(dbFile)) {
            MigrationRunner runner = new MigrationRunner(db);
            assertThat(runner.currentVersion()).isEqualTo(1);
            int rerun = runner.apply(SkyblockMigrations.getMigrations(db.dialect()));
            assertThat(rerun).isEqualTo(0);
        }
    }

    @Test
    @DisplayName("9. Explicit dialect dispatch: unsupported upstream dialects fail fast with IllegalArgumentException")
    void unsupportedUpstreamDialectsAreRejected() {
        assertThatThrownBy(() -> SkyblockMigrations.getMigrations(Dialect.H2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported SQL dialect: H2");

        assertThatThrownBy(() -> SkyblockMigrations.getMigrations(Dialect.GENERIC))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported SQL dialect: GENERIC");
    }

    @Test
    @DisplayName("10. Null dialect is rejected with NullPointerException")
    void nullDialectThrowsNpe() {
        assertThatThrownBy(() -> SkyblockMigrations.getMigrations(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("dialect");
    }

    private static void enableForeignKeys(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON;");
        }
    }

    private static void execute(Connection conn, String sql) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    private static int queryCount(Connection conn, String sql) throws SQLException {
        try (Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static Set<String> getColumnNames(DatabaseMetaData meta, String tableName) throws SQLException {
        Set<String> columns = new HashSet<>();
        try (ResultSet rs = meta.getColumns(null, null, tableName, "%")) {
            while (rs.next()) {
                columns.add(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
            }
        }
        return columns;
    }
}
