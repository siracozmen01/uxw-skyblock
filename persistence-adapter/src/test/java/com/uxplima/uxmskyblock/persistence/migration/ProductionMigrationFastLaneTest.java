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
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fast-lane SQLite test suite verifying the production persistence foundation (WP2-001 & WP2-003).
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
            assertThat(applied).isEqualTo(SkyblockMigrations.LATEST_VERSION);
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

                // Canonical tables that MUST exist in V5
                assertThat(tables)
                        .contains(
                                "player_accounts",
                                "player_profiles",
                                "player_sessions",
                                "profile_inventories",
                                "inventory_mutation_journals",
                                "inventory_mutation_participants",
                                "profile_switch_operations",
                                "islands",
                                "island_authorities",
                                "island_locations",
                                "island_members",
                                "island_roles",
                                "island_role_permissions",
                                "island_flags",
                                "uxmlib_schema_history");

                // Future / deferred tables that MUST NOT exist
                assertThat(tables).doesNotContain("outbox_events", "inbox_events", "island_banks");
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

                // profile_inventories columns (V2)
                Set<String> inventoryCols = getColumnNames(meta, "profile_inventories");
                assertThat(inventoryCols)
                        .containsExactlyInAnyOrder(
                                "profile_id",
                                "profile_inventory_version",
                                "inventory_nbt",
                                "enderchest_nbt",
                                "experience_points",
                                "health",
                                "food_level",
                                "saturation",
                                "active_potion_effects_nbt",
                                "logout_world",
                                "logout_x",
                                "logout_y",
                                "logout_z",
                                "gamemode",
                                "flight_allowed",
                                "updated_at");

                // inventory_mutation_journals columns (V3)
                Set<String> journalCols = getColumnNames(meta, "inventory_mutation_journals");
                assertThat(journalCols)
                        .containsExactlyInAnyOrder(
                                "operation_id",
                                "operation_type",
                                "state",
                                "participant_count",
                                "payload",
                                "expires_at",
                                "created_at",
                                "updated_at");

                // inventory_mutation_participants columns (V3)
                Set<String> participantCols = getColumnNames(meta, "inventory_mutation_participants");
                assertThat(participantCols)
                        .containsExactlyInAnyOrder(
                                "operation_id",
                                "participant_index",
                                "inventory_type",
                                "owner_root_type",
                                "owner_root_id",
                                "expected_version",
                                "authority_type",
                                "authority_id",
                                "authority_epoch",
                                "before_fingerprint",
                                "after_fingerprint",
                                "durable_apply_state",
                                "mutation_delta_payload",
                                "updated_at");

                // profile_switch_operations columns (V4)
                Set<String> switchCols = getColumnNames(meta, "profile_switch_operations");
                assertThat(switchCols)
                        .containsExactlyInAnyOrder(
                                "operation_id",
                                "player_uuid",
                                "from_profile_id",
                                "to_profile_id",
                                "state",
                                "source_snapshot_blob",
                                "target_snapshot_blob",
                                "failure_reason",
                                "created_at",
                                "updated_at");

                // islands columns (V5)
                Set<String> islandCols = getColumnNames(meta, "islands");
                assertThat(islandCols)
                        .containsExactlyInAnyOrder(
                                "id",
                                "owner_profile_id",
                                "owner_account_uuid",
                                "custom_name",
                                "lifecycle",
                                "economic_state",
                                "administrative_state",
                                "freeze_reason",
                                "level_score",
                                "net_worth_minor_units",
                                "version",
                                "created_at",
                                "updated_at");

                // island_authorities columns (V5)
                Set<String> authCols = getColumnNames(meta, "island_authorities");
                assertThat(authCols)
                        .containsExactlyInAnyOrder(
                                "island_id",
                                "authoritative_node",
                                "authority_epoch",
                                "lease_expires_at",
                                "last_heartbeat_at",
                                "updated_at");

                // island_locations columns (V5)
                Set<String> locCols = getColumnNames(meta, "island_locations");
                assertThat(locCols)
                        .containsExactlyInAnyOrder(
                                "island_id",
                                "world_name",
                                "center_x",
                                "center_z",
                                "min_x",
                                "min_z",
                                "max_x",
                                "max_z",
                                "spawn_x",
                                "spawn_y",
                                "spawn_z",
                                "spawn_yaw",
                                "spawn_pitch");

                // island_members columns (V5)
                Set<String> memCols = getColumnNames(meta, "island_members");
                assertThat(memCols)
                        .containsExactlyInAnyOrder("island_id", "player_uuid", "profile_id", "role_id", "joined_at");

                // island_roles columns (V5)
                Set<String> roleCols = getColumnNames(meta, "island_roles");
                assertThat(roleCols)
                        .containsExactlyInAnyOrder("island_id", "role_id", "weight", "display_name", "is_system");

                // island_role_permissions columns (V5)
                Set<String> permCols = getColumnNames(meta, "island_role_permissions");
                assertThat(permCols).containsExactlyInAnyOrder("island_id", "role_id", "permission");

                // island_flags columns (V5)
                Set<String> flagCols = getColumnNames(meta, "island_flags");
                assertThat(flagCols).containsExactlyInAnyOrder("island_id", "flag_name", "flag_value");
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
            assertThat(applied).isEqualTo(SkyblockMigrations.LATEST_VERSION);
            assertThat(runner.currentVersion()).isEqualTo(SkyblockMigrations.LATEST_VERSION);
        }

        // Second run: reopen existing database file
        try (Database db = DatabaseTestFixture.createSqliteFile(dbFile)) {
            MigrationRunner runner = new MigrationRunner(db);
            assertThat(runner.currentVersion()).isEqualTo(SkyblockMigrations.LATEST_VERSION);
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

    @Test
    @DisplayName("11. Upgrade from existing V1 database applies V2 cleanly and preserves data")
    void upgradeFromV1AppliesV2Cleanly() throws Exception {
        try (Database db = DatabaseTestFixture.createSqliteInMemory()) {
            MigrationRunner runner = new MigrationRunner(db);

            // 1. Apply only V1 migration
            List<Migration> allMigrations = SkyblockMigrations.getMigrations(db.dialect());
            Migration v1 = allMigrations.get(0);
            int v1Applied = runner.apply(List.of(v1));
            assertThat(v1Applied).isEqualTo(1);
            assertThat(runner.currentVersion()).isEqualTo(1);

            // 2. Verify pre-V2 schema state: profile_inventories does NOT exist
            try (Connection conn = db.connection()) {
                DatabaseMetaData meta = conn.getMetaData();
                try (ResultSet rs = meta.getTables(null, null, "profile_inventories", null)) {
                    assertThat(rs.next()).isFalse();
                }
            }

            // 3. Seed V1 data
            try (Connection conn = db.connection()) {
                enableForeignKeys(conn);
                execute(conn, "INSERT INTO player_accounts (player_uuid) VALUES ('p-upg')");
                execute(conn, "INSERT INTO player_profiles (profile_id, player_uuid) VALUES ('prof-upg', 'p-upg')");
                execute(conn, "UPDATE player_accounts SET active_profile_id = 'prof-upg' WHERE player_uuid = 'p-upg'");
                execute(conn, """
                        INSERT INTO player_sessions (
                            player_uuid, active_profile_id, authoritative_node, session_epoch,
                            state, last_durable_inventory_version, lease_expires_at
                        ) VALUES ('p-upg', 'prof-upg', 'node-1', 1, 'ACTIVE', 1, CURRENT_TIMESTAMP)
                        """);
            }

            // 4. Upgrade by applying V1 + V2 migrations (only V2 should be applied)
            int v2Applied = runner.apply(allMigrations.subList(0, 2));
            assertThat(v2Applied).isEqualTo(1);
            assertThat(runner.currentVersion()).isEqualTo(2);

            // 5. Verify seeded V1 data preserved
            try (Connection conn = db.connection()) {
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM player_accounts WHERE player_uuid = 'p-upg'"))
                        .isEqualTo(1);
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM player_profiles WHERE profile_id = 'prof-upg'"))
                        .isEqualTo(1);
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM player_sessions WHERE player_uuid = 'p-upg'"))
                        .isEqualTo(1);

                // 6. Verify profile_inventories table now exists and can accept rows
                execute(conn, """
                        INSERT INTO profile_inventories (profile_id, inventory_nbt, enderchest_nbt)
                        VALUES ('prof-upg', X'0102', X'0304')
                        """);
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM profile_inventories WHERE profile_id = 'prof-upg'"))
                        .isEqualTo(1);
            }

            // 7. Rerun and assert zero migrations applied
            int rerun = runner.apply(allMigrations.subList(0, 2));
            assertThat(rerun).isEqualTo(0);
            assertThat(runner.currentVersion()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("12. Upgrade from existing V2 database applies V3 cleanly and preserves data")
    void upgradeFromV2AppliesV3Cleanly() throws Exception {
        try (Database db = DatabaseTestFixture.createSqliteInMemory()) {
            MigrationRunner runner = new MigrationRunner(db);

            // 1. Apply V1 + V2 migrations
            List<Migration> allMigrations = SkyblockMigrations.getMigrations(db.dialect());
            int v2Applied = runner.apply(allMigrations.subList(0, 2));
            assertThat(v2Applied).isEqualTo(2);
            assertThat(runner.currentVersion()).isEqualTo(2);

            // 2. Verify pre-V3 schema state: inventory_mutation_journals does NOT exist
            try (Connection conn = db.connection()) {
                DatabaseMetaData meta = conn.getMetaData();
                try (ResultSet rs = meta.getTables(null, null, "inventory_mutation_journals", null)) {
                    assertThat(rs.next()).isFalse();
                }
            }

            // 3. Seed V2 data
            try (Connection conn = db.connection()) {
                enableForeignKeys(conn);
                execute(conn, "INSERT INTO player_accounts (player_uuid) VALUES ('p-v3-upg')");
                execute(
                        conn,
                        "INSERT INTO player_profiles (profile_id, player_uuid) VALUES ('prof-v3-upg', 'p-v3-upg')");
                execute(
                        conn,
                        "UPDATE player_accounts SET active_profile_id = 'prof-v3-upg' WHERE player_uuid = 'p-v3-upg'");
                execute(conn, """
                        INSERT INTO player_sessions (
                            player_uuid, active_profile_id, authoritative_node, session_epoch,
                            state, last_durable_inventory_version, lease_expires_at
                        ) VALUES ('p-v3-upg', 'prof-v3-upg', 'node-1', 1, 'ACTIVE', 1, CURRENT_TIMESTAMP)
                        """);
                execute(conn, """
                        INSERT INTO profile_inventories (profile_id, inventory_nbt, enderchest_nbt)
                        VALUES ('prof-v3-upg', X'0102', X'0304')
                        """);
            }

            // 4. Upgrade by applying V3 migration
            int v3Applied = runner.apply(allMigrations.subList(0, 3));
            assertThat(v3Applied).isEqualTo(1);
            assertThat(runner.currentVersion()).isEqualTo(3);

            // 5. Verify seeded V1 and V2 data preserved
            try (Connection conn = db.connection()) {
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM player_accounts WHERE player_uuid = 'p-v3-upg'"))
                        .isEqualTo(1);
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM player_profiles WHERE profile_id = 'prof-v3-upg'"))
                        .isEqualTo(1);
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM player_sessions WHERE player_uuid = 'p-v3-upg'"))
                        .isEqualTo(1);
                assertThat(queryCount(
                                conn, "SELECT COUNT(*) FROM profile_inventories WHERE profile_id = 'prof-v3-upg'"))
                        .isEqualTo(1);

                // 6. Verify inventory_mutation_journals and participants accept rows
                execute(conn, """
                        INSERT INTO inventory_mutation_journals (
                            operation_id, operation_type, state, participant_count, payload, expires_at
                        ) VALUES ('op-1', 'TRADE', 'INTENT', 1, '{}', CURRENT_TIMESTAMP)
                        """);
                execute(conn, """
                        INSERT INTO inventory_mutation_participants (
                            operation_id, participant_index, inventory_type, owner_root_type, owner_root_id,
                            expected_version, authority_type, authority_id, authority_epoch,
                            before_fingerprint, after_fingerprint, durable_apply_state, mutation_delta_payload
                        ) VALUES ('op-1', 0, 'PLAYER_INVENTORY', 'PROFILE', 'prof-v3-upg', 1, 'SERVER_NODE', 'node-1', 1, 'fp1', 'fp2', 'PENDING', '{}')
                        """);

                assertThat(queryCount(
                                conn, "SELECT COUNT(*) FROM inventory_mutation_journals WHERE operation_id = 'op-1'"))
                        .isEqualTo(1);
                assertThat(queryCount(
                                conn,
                                "SELECT COUNT(*) FROM inventory_mutation_participants WHERE operation_id = 'op-1'"))
                        .isEqualTo(1);
            }

            // 7. Rerun and assert zero migrations applied
            int rerun = runner.apply(allMigrations.subList(0, 3));
            assertThat(rerun).isEqualTo(0);
            assertThat(runner.currentVersion()).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("13. Upgrade from existing V3 database applies V4 cleanly and preserves data")
    void upgradeFromV3AppliesV4Cleanly() throws Exception {
        try (Database db = DatabaseTestFixture.createSqliteInMemory()) {
            MigrationRunner runner = new MigrationRunner(db);

            // 1. Apply V1 + V2 + V3 migrations
            List<Migration> allMigrations = SkyblockMigrations.getMigrations(db.dialect());
            int v3Applied = runner.apply(allMigrations.subList(0, 3));
            assertThat(v3Applied).isEqualTo(3);
            assertThat(runner.currentVersion()).isEqualTo(3);

            // 2. Verify pre-V4 schema state: profile_switch_operations does NOT exist
            try (Connection conn = db.connection()) {
                DatabaseMetaData meta = conn.getMetaData();
                try (ResultSet rs = meta.getTables(null, null, "profile_switch_operations", null)) {
                    assertThat(rs.next()).isFalse();
                }
            }

            // 3. Seed V3 data
            try (Connection conn = db.connection()) {
                enableForeignKeys(conn);
                execute(conn, "INSERT INTO player_accounts (player_uuid) VALUES ('p-v4-upg')");
                execute(
                        conn,
                        "INSERT INTO player_profiles (profile_id, player_uuid) VALUES ('prof-v4-src', 'p-v4-upg')");
                execute(
                        conn,
                        "INSERT INTO player_profiles (profile_id, player_uuid) VALUES ('prof-v4-tgt', 'p-v4-upg')");
            }

            // 4. Upgrade by applying V1..V4 migrations (only V4 should be applied)
            int v4Applied = runner.apply(allMigrations.subList(0, 4));
            assertThat(v4Applied).isEqualTo(1);
            assertThat(runner.currentVersion()).isEqualTo(4);

            // 5. Verify seeded data preserved
            try (Connection conn = db.connection()) {
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM player_accounts WHERE player_uuid = 'p-v4-upg'"))
                        .isEqualTo(1);

                // 6. Verify profile_switch_operations table accepts rows
                execute(conn, """
                        INSERT INTO profile_switch_operations (
                            operation_id, player_uuid, from_profile_id, to_profile_id, state
                        ) VALUES ('sw-1', 'p-v4-upg', 'prof-v4-src', 'prof-v4-tgt', 'PREPARING')
                        """);
                assertThat(queryCount(
                                conn, "SELECT COUNT(*) FROM profile_switch_operations WHERE operation_id = 'sw-1'"))
                        .isEqualTo(1);
            }

            // 7. Rerun and assert zero migrations applied
            int rerun = runner.apply(allMigrations.subList(0, 4));
            assertThat(rerun).isEqualTo(0);
            assertThat(runner.currentVersion()).isEqualTo(4);
        }
    }

    @Test
    @DisplayName("14. Upgrade from existing V4 database applies V5 cleanly and preserves data")
    void upgradeFromV4AppliesV5Cleanly() throws Exception {
        try (Database db = DatabaseTestFixture.createSqliteInMemory()) {
            MigrationRunner runner = new MigrationRunner(db);

            // 1. Apply V1 + V2 + V3 + V4 migrations
            List<Migration> allMigrations = SkyblockMigrations.getMigrations(db.dialect());
            int v4Applied = runner.apply(allMigrations.subList(0, 4));
            assertThat(v4Applied).isEqualTo(4);
            assertThat(runner.currentVersion()).isEqualTo(4);

            // 2. Verify pre-V5 schema state: islands does NOT exist
            try (Connection conn = db.connection()) {
                DatabaseMetaData meta = conn.getMetaData();
                try (ResultSet rs = meta.getTables(null, null, "islands", null)) {
                    assertThat(rs.next()).isFalse();
                }
            }

            // 3. Seed V4 data
            try (Connection conn = db.connection()) {
                enableForeignKeys(conn);
                execute(conn, "INSERT INTO player_accounts (player_uuid) VALUES ('p-v5-upg')");
                execute(
                        conn,
                        "INSERT INTO player_profiles (profile_id, player_uuid) VALUES ('prof-v5-src', 'p-v5-upg')");
                execute(
                        conn,
                        "INSERT INTO player_profiles (profile_id, player_uuid) VALUES ('prof-v5-tgt', 'p-v5-upg')");
                execute(conn, """
                        INSERT INTO profile_switch_operations (
                            operation_id, player_uuid, from_profile_id, to_profile_id, state
                        ) VALUES ('sw-v5', 'p-v5-upg', 'prof-v5-src', 'prof-v5-tgt', 'PREPARING')
                        """);
            }

            // 4. Upgrade by applying all migrations (only V5 should be applied)
            int v5Applied = runner.apply(allMigrations);
            assertThat(v5Applied).isEqualTo(1);
            assertThat(runner.currentVersion()).isEqualTo(5);

            // 5. Verify seeded data preserved
            try (Connection conn = db.connection()) {
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM player_accounts WHERE player_uuid = 'p-v5-upg'"))
                        .isEqualTo(1);
                assertThat(queryCount(
                                conn, "SELECT COUNT(*) FROM profile_switch_operations WHERE operation_id = 'sw-v5'"))
                        .isEqualTo(1);

                // 6. Verify islands table accepts rows
                execute(conn, """
                        INSERT INTO islands (id, owner_profile_id, owner_account_uuid)
                        VALUES ('isl-v5', 'prof-v5-src', 'p-v5-upg')
                        """);
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM islands WHERE id = 'isl-v5'"))
                        .isEqualTo(1);
            }

            // 7. Rerun and assert zero migrations applied
            int rerun = runner.apply(allMigrations);
            assertThat(rerun).isEqualTo(0);
            assertThat(runner.currentVersion()).isEqualTo(5);
        }
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
