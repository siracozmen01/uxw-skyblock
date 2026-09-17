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

                // Canonical tables that MUST exist in V6
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
                                "island_banks",
                                "bank_transactions",
                                "processed_operations",
                                "island_upgrades",
                                "backup_operations",
                                "outbox_events",
                                "consumer_inbox",
                                "world_grid_allocations",
                                "uxmlib_schema_history");

                // Future / deferred tables that MUST NOT exist
                assertThat(tables).doesNotContain("inbox_events");
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

                // island_banks columns (V6)
                Set<String> bankCols = getColumnNames(meta, "island_banks");
                assertThat(bankCols)
                        .containsExactlyInAnyOrder(
                                "island_id",
                                "primary_balance_minor_units",
                                "crystals_balance",
                                "exp_balance",
                                "version",
                                "updated_at");

                // bank_transactions columns (V6)
                Set<String> txCols = getColumnNames(meta, "bank_transactions");
                assertThat(txCols)
                        .containsExactlyInAnyOrder(
                                "transaction_id",
                                "operation_id",
                                "island_id",
                                "actor_uuid",
                                "currency_id",
                                "currency_scale",
                                "delta_amount_minor_units",
                                "resulting_balance_minor_units",
                                "reason",
                                "created_at");

                // processed_operations columns (V6)
                Set<String> opCols = getColumnNames(meta, "processed_operations");
                assertThat(opCols)
                        .containsExactlyInAnyOrder(
                                "operation_id",
                                "operation_scope",
                                "actor_id",
                                "idempotency_key",
                                "operation_type",
                                "resource_id",
                                "status",
                                "result_code",
                                "result_payload",
                                "created_at",
                                "completed_at");

                // island_upgrades columns (V7)
                Set<String> upgCols = getColumnNames(meta, "island_upgrades");
                assertThat(upgCols).containsExactlyInAnyOrder("island_id", "upgrade_key", "tier", "updated_at");

                // backup_operations columns (V8)
                Set<String> bakCols = getColumnNames(meta, "backup_operations");
                assertThat(bakCols)
                        .containsExactlyInAnyOrder(
                                "backup_set_id",
                                "backup_type",
                                "target_root_type_id",
                                "target_root_key",
                                "state",
                                "authority_epoch",
                                "db_version",
                                "schema_version",
                                "plugin_version",
                                "failure_reason",
                                "created_at",
                                "completed_at",
                                "updated_at");

                // outbox_events columns (V9)
                Set<String> outboxCols = getColumnNames(meta, "outbox_events");
                assertThat(outboxCols)
                        .containsExactlyInAnyOrder(
                                "event_id",
                                "event_type",
                                "aggregate_id",
                                "payload",
                                "status",
                                "retry_count",
                                "next_attempt_at",
                                "claim_owner",
                                "claim_token",
                                "claim_expires_at",
                                "last_error",
                                "created_at",
                                "processed_at");

                // consumer_inbox columns (V9)
                Set<String> inboxCols = getColumnNames(meta, "consumer_inbox");
                assertThat(inboxCols).containsExactlyInAnyOrder("consumer_name", "event_id", "processed_at");

                // world_grid_allocations columns (V10)
                Set<String> gridCols = getColumnNames(meta, "world_grid_allocations");
                assertThat(gridCols)
                        .containsExactlyInAnyOrder(
                                "sequence_index",
                                "world_name",
                                "center_x",
                                "center_z",
                                "island_id",
                                "allocated_by_node",
                                "allocated_at");
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

            // 4. Upgrade by applying all migrations up to V5 (only V5 should be applied)
            int v5Applied = runner.apply(allMigrations.subList(0, 5));
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
            int rerun = runner.apply(allMigrations.subList(0, 5));
            assertThat(rerun).isEqualTo(0);
            assertThat(runner.currentVersion()).isEqualTo(5);
        }
    }

    @Test
    @DisplayName("15. Upgrade from existing V5 database applies V6 cleanly and preserves data")
    void upgradeFromV5AppliesV6Cleanly() throws Exception {
        try (Database db = DatabaseTestFixture.createSqliteInMemory()) {
            MigrationRunner runner = new MigrationRunner(db);

            // 1. Apply V1..V5 migrations
            List<Migration> allMigrations = SkyblockMigrations.getMigrations(db.dialect());
            int v5Applied = runner.apply(allMigrations.subList(0, 5));
            assertThat(v5Applied).isEqualTo(5);
            assertThat(runner.currentVersion()).isEqualTo(5);

            // 2. Verify pre-V6 schema state: island_banks does NOT exist
            try (Connection conn = db.connection()) {
                DatabaseMetaData meta = conn.getMetaData();
                try (ResultSet rs = meta.getTables(null, null, "island_banks", null)) {
                    assertThat(rs.next()).isFalse();
                }
            }

            // 3. Seed V5 data
            try (Connection conn = db.connection()) {
                enableForeignKeys(conn);
                execute(conn, "INSERT INTO player_accounts (player_uuid) VALUES ('p-v6-upg')");
                execute(conn, "INSERT INTO player_profiles (profile_id, player_uuid) VALUES ('prof-v6', 'p-v6-upg')");
                execute(conn, """
                        INSERT INTO islands (id, owner_profile_id, owner_account_uuid)
                        VALUES ('isl-v6', 'prof-v6', 'p-v6-upg')
                        """);
            }

            // 4. Upgrade by applying V6 migration (only V6 should be applied)
            int v6Applied = runner.apply(allMigrations.subList(0, 6));
            assertThat(v6Applied).isEqualTo(1);
            assertThat(runner.currentVersion()).isEqualTo(6);

            // 5. Verify seeded data preserved
            try (Connection conn = db.connection()) {
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM islands WHERE id = 'isl-v6'"))
                        .isEqualTo(1);

                // 6. Verify island_banks and bank_transactions accept rows
                execute(conn, """
                        INSERT INTO island_banks (island_id, primary_balance_minor_units)
                        VALUES ('isl-v6', 12345)
                        """);
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM island_banks WHERE island_id = 'isl-v6'"))
                        .isEqualTo(1);
            }

            // 7. Rerun and assert zero migrations applied
            int rerun = runner.apply(allMigrations.subList(0, 6));
            assertThat(rerun).isEqualTo(0);
            assertThat(runner.currentVersion()).isEqualTo(6);
        }
    }

    @Test
    @DisplayName(
            "16. Step-by-step upgrade from V6 to V7 preserves existing bank and island data and enables upgrade engine")
    void stepByStepUpgradeFromV6ToV7PreservesData() throws Exception {
        try (Database db = DatabaseTestFixture.createSqliteInMemory()) {
            MigrationRunner runner = new MigrationRunner(db);

            // 1. Migrate up to V6
            List<Migration> allMigrations = SkyblockMigrations.getMigrations(db.dialect());
            int v6Applied = runner.apply(allMigrations.subList(0, 6));
            assertThat(v6Applied).isEqualTo(6);
            assertThat(runner.currentVersion()).isEqualTo(6);

            // 2. Verify pre-V7 schema state: island_upgrades does NOT exist
            try (Connection conn = db.connection()) {
                DatabaseMetaData meta = conn.getMetaData();
                try (ResultSet rs = meta.getTables(null, null, "island_upgrades", null)) {
                    assertThat(rs.next()).isFalse();
                }
            }

            // 3. Seed V6 data
            try (Connection conn = db.connection()) {
                enableForeignKeys(conn);
                execute(conn, "INSERT INTO player_accounts (player_uuid) VALUES ('p-v7-upg')");
                execute(conn, "INSERT INTO player_profiles (profile_id, player_uuid) VALUES ('prof-v7', 'p-v7-upg')");
                execute(conn, """
                        INSERT INTO islands (id, owner_profile_id, owner_account_uuid)
                        VALUES ('isl-v7', 'prof-v7', 'p-v7-upg')
                        """);
                execute(conn, """
                        INSERT INTO island_banks (island_id, primary_balance_minor_units)
                        VALUES ('isl-v7', 50000)
                        """);
            }

            // 4. Upgrade by applying migrations up to V7 (only V7 should be applied)
            int v7Applied = runner.apply(allMigrations.subList(0, 7));
            assertThat(v7Applied).isEqualTo(1);
            assertThat(runner.currentVersion()).isEqualTo(7);

            // 5. Verify seeded data preserved
            try (Connection conn = db.connection()) {
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM islands WHERE id = 'isl-v7'"))
                        .isEqualTo(1);
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM island_banks WHERE island_id = 'isl-v7'"))
                        .isEqualTo(1);

                // 6. Verify island_upgrades accepts rows
                execute(conn, """
                        INSERT INTO island_upgrades (island_id, upgrade_key, tier)
                        VALUES ('isl-v7', 'SIZE', 1)
                        """);
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM island_upgrades WHERE island_id = 'isl-v7'"))
                        .isEqualTo(1);
            }

            // 7. Rerun and assert zero migrations applied
            int rerun = runner.apply(allMigrations.subList(0, 7));
            assertThat(rerun).isEqualTo(0);
            assertThat(runner.currentVersion()).isEqualTo(7);
        }
    }

    @Test
    @DisplayName(
            "17. Step-by-step upgrade from V7 to V8 preserves existing upgrade and island data and enables backup operations catalog")
    void stepByStepUpgradeFromV7ToV8PreservesData() throws Exception {
        try (Database db = DatabaseTestFixture.createSqliteInMemory()) {
            MigrationRunner runner = new MigrationRunner(db);

            // 1. Migrate up to V7
            List<Migration> allMigrations = SkyblockMigrations.getMigrations(db.dialect());
            int v7Applied = runner.apply(allMigrations.subList(0, 7));
            assertThat(v7Applied).isEqualTo(7);
            assertThat(runner.currentVersion()).isEqualTo(7);

            // 2. Verify pre-V8 schema state: backup_operations does NOT exist
            try (Connection conn = db.connection()) {
                DatabaseMetaData meta = conn.getMetaData();
                try (ResultSet rs = meta.getTables(null, null, "backup_operations", null)) {
                    assertThat(rs.next()).isFalse();
                }
            }

            // 3. Seed V7 data
            try (Connection conn = db.connection()) {
                enableForeignKeys(conn);
                execute(conn, "INSERT INTO player_accounts (player_uuid) VALUES ('p-v8-upg')");
                execute(conn, "INSERT INTO player_profiles (profile_id, player_uuid) VALUES ('prof-v8', 'p-v8-upg')");
                execute(conn, """
                        INSERT INTO islands (id, owner_profile_id, owner_account_uuid)
                        VALUES ('isl-v8', 'prof-v8', 'p-v8-upg')
                        """);
                execute(conn, """
                        INSERT INTO island_banks (island_id, primary_balance_minor_units)
                        VALUES ('isl-v8', 50000)
                        """);
                execute(conn, """
                        INSERT INTO island_upgrades (island_id, upgrade_key, tier)
                        VALUES ('isl-v8', 'SIZE', 2)
                        """);
            }

            // 4. Upgrade by applying migrations up to V8 (only V8 should be applied)
            int v8Applied = runner.apply(allMigrations.subList(0, 8));
            assertThat(v8Applied).isEqualTo(1);
            assertThat(runner.currentVersion()).isEqualTo(8);

            // 5. Verify seeded data preserved
            try (Connection conn = db.connection()) {
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM islands WHERE id = 'isl-v8'"))
                        .isEqualTo(1);
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM island_banks WHERE island_id = 'isl-v8'"))
                        .isEqualTo(1);
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM island_upgrades WHERE island_id = 'isl-v8'"))
                        .isEqualTo(1);

                // 6. Verify backup_operations accepts rows
                execute(conn, """
                        INSERT INTO backup_operations (
                            backup_set_id, backup_type, target_root_type_id, target_root_key,
                            state, authority_epoch, db_version, schema_version, plugin_version
                        ) VALUES ('bak-v8', 'ROOT_BACKUP', 'ISLAND', 'isl-v8', 'AVAILABLE', 1, 100, 8, '1.0.0')
                        """);
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM backup_operations WHERE backup_set_id = 'bak-v8'"))
                        .isEqualTo(1);
            }

            // 7. Rerun and assert zero migrations applied
            int rerun = runner.apply(allMigrations.subList(0, 8));
            assertThat(rerun).isEqualTo(0);
            assertThat(runner.currentVersion()).isEqualTo(8);
        }
    }

    @Test
    @DisplayName(
            "18. Step-by-step upgrade from V8 to V9 preserves existing backup data and enables transactional outbox and consumer inbox")
    void stepByStepUpgradeFromV8ToV9PreservesData() throws Exception {
        try (Database db = DatabaseTestFixture.createSqliteInMemory()) {
            MigrationRunner runner = new MigrationRunner(db);

            // 1. Migrate up to V8
            List<Migration> allMigrations = SkyblockMigrations.getMigrations(db.dialect());
            int v8Applied = runner.apply(allMigrations.subList(0, 8));
            assertThat(v8Applied).isEqualTo(8);
            assertThat(runner.currentVersion()).isEqualTo(8);

            // 2. Verify pre-V9 schema state: outbox_events and consumer_inbox do NOT exist
            try (Connection conn = db.connection()) {
                DatabaseMetaData meta = conn.getMetaData();
                try (ResultSet rs = meta.getTables(null, null, "outbox_events", null)) {
                    assertThat(rs.next()).isFalse();
                }
                try (ResultSet rs = meta.getTables(null, null, "consumer_inbox", null)) {
                    assertThat(rs.next()).isFalse();
                }
            }

            // 3. Seed V8 data
            try (Connection conn = db.connection()) {
                enableForeignKeys(conn);
                execute(conn, "INSERT INTO player_accounts (player_uuid) VALUES ('p-v9-upg')");
                execute(conn, "INSERT INTO player_profiles (profile_id, player_uuid) VALUES ('prof-v9', 'p-v9-upg')");
                execute(conn, """
                        INSERT INTO islands (id, owner_profile_id, owner_account_uuid)
                        VALUES ('isl-v9', 'prof-v9', 'p-v9-upg')
                        """);
                execute(conn, """
                        INSERT INTO island_banks (island_id, primary_balance_minor_units)
                        VALUES ('isl-v9', 50000)
                        """);
                execute(conn, """
                        INSERT INTO island_upgrades (island_id, upgrade_key, tier)
                        VALUES ('isl-v9', 'SIZE', 2)
                        """);
                execute(conn, """
                        INSERT INTO backup_operations (
                            backup_set_id, backup_type, target_root_type_id, target_root_key,
                            state, authority_epoch, db_version, schema_version, plugin_version
                        ) VALUES ('bak-v9', 'ROOT_BACKUP', 'ISLAND', 'isl-v9', 'AVAILABLE', 1, 100, 8, '1.0.0')
                        """);
            }

            // 4. Upgrade by applying V9 migration (only V9 should be applied)
            int v9Applied = runner.apply(allMigrations.subList(0, 9));
            assertThat(v9Applied).isEqualTo(1);
            assertThat(runner.currentVersion()).isEqualTo(9);

            // 5. Verify seeded data preserved
            try (Connection conn = db.connection()) {
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM islands WHERE id = 'isl-v9'"))
                        .isEqualTo(1);
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM island_banks WHERE island_id = 'isl-v9'"))
                        .isEqualTo(1);
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM island_upgrades WHERE island_id = 'isl-v9'"))
                        .isEqualTo(1);
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM backup_operations WHERE backup_set_id = 'bak-v9'"))
                        .isEqualTo(1);

                // 6. Verify outbox_events accepts rows
                execute(conn, """
                        INSERT INTO outbox_events (
                            event_id, event_type, aggregate_id, payload, status
                        ) VALUES ('evt-v9-1', 'ISLAND_CREATED', 'isl-v9', '{"tier":1}', 'PENDING')
                        """);
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM outbox_events WHERE event_id = 'evt-v9-1'"))
                        .isEqualTo(1);

                // 7. Verify consumer_inbox accepts rows
                execute(conn, """
                        INSERT INTO consumer_inbox (
                            consumer_name, event_id
                        ) VALUES ('feed-consumer', 'evt-v9-1')
                        """);
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM consumer_inbox WHERE event_id = 'evt-v9-1'"))
                        .isEqualTo(1);
            }

            // 8. Rerun and assert zero migrations applied
            int rerun = runner.apply(allMigrations.subList(0, 9));
            assertThat(rerun).isEqualTo(0);
            assertThat(runner.currentVersion()).isEqualTo(9);
        }
    }

    @Test
    @DisplayName("13. Step-by-step upgrade from V9 to V10 preserves data and adds world_grid_allocations")
    void stepByStepUpgradeFromV9ToV10PreservesData() throws Exception {
        try (Database db = DatabaseTestFixture.createSqliteInMemory()) {
            MigrationRunner runner = new MigrationRunner(db);

            // 1. Migrate up to V9
            List<Migration> allMigrations = SkyblockMigrations.getMigrations(db.dialect());
            int v9Applied = runner.apply(allMigrations.subList(0, 9));
            assertThat(v9Applied).isEqualTo(9);
            assertThat(runner.currentVersion()).isEqualTo(9);

            // 2. Verify pre-V10 schema state: world_grid_allocations does NOT exist
            try (Connection conn = db.connection()) {
                DatabaseMetaData meta = conn.getMetaData();
                try (ResultSet rs = meta.getTables(null, null, "world_grid_allocations", null)) {
                    assertThat(rs.next()).isFalse();
                }
            }

            // 3. Seed V9 data
            try (Connection conn = db.connection()) {
                enableForeignKeys(conn);
                execute(conn, "INSERT INTO player_accounts (player_uuid) VALUES ('p-v10-upg')");
                execute(conn, "INSERT INTO player_profiles (profile_id, player_uuid) VALUES ('prof-v10', 'p-v10-upg')");
                execute(conn, """
                        INSERT INTO islands (id, owner_profile_id, owner_account_uuid)
                        VALUES ('isl-v10', 'prof-v10', 'p-v10-upg')
                        """);
                execute(conn, """
                        INSERT INTO outbox_events (
                            event_id, event_type, aggregate_id, payload, status
                        ) VALUES ('evt-v10-1', 'ISLAND_CREATED', 'isl-v10', '{"tier":1}', 'PENDING')
                        """);
            }

            // 4. Upgrade by applying all migrations (only V10 should be applied)
            int v10Applied = runner.apply(allMigrations);
            assertThat(v10Applied).isEqualTo(1);
            assertThat(runner.currentVersion()).isEqualTo(10);

            // 5. Verify seeded data preserved
            try (Connection conn = db.connection()) {
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM islands WHERE id = 'isl-v10'"))
                        .isEqualTo(1);
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM outbox_events WHERE event_id = 'evt-v10-1'"))
                        .isEqualTo(1);

                // 6. Verify world_grid_allocations accepts rows
                execute(conn, """
                        INSERT INTO world_grid_allocations (
                            sequence_index, world_name, center_x, center_z, island_id, allocated_by_node
                        ) VALUES (0, 'world', 0, 0, 'isl-v10', 'node-1')
                        """);
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM world_grid_allocations WHERE sequence_index = 0"))
                        .isEqualTo(1);
            }

            // 7. Rerun and assert zero migrations applied
            int rerun = runner.apply(allMigrations);
            assertThat(rerun).isEqualTo(0);
            assertThat(runner.currentVersion()).isEqualTo(10);
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
