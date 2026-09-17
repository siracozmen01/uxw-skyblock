package com.uxplima.uxmskyblock.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

/**
 * P2 Integration Lane test suite verifying production persistence foundation (WP2-001 & WP2-003)
 * across real server SQL databases (MariaDB 10.11.11 and PostgreSQL 15.12-alpine).
 *
 * <p>Tagged {@code @Tag("database-integration")} so it runs exclusively under the dedicated
 * {@code :persistence-adapter:databaseIntegrationTest} task.
 */
@Tag("database-integration")
@Execution(ExecutionMode.SAME_THREAD)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProductionMigrationIntegrationTest {

    private static MariaDBContainer<?> mariaDbContainer;
    private static PostgreSQLContainer<?> postgresContainer;

    private static Database mariaDatabase;
    private static Database postgresDatabase;

    private static MigrationRunner mariaRunner;
    private static MigrationRunner postgresRunner;

    @BeforeAll
    static void setUpAll() {
        mariaDbContainer = DatabaseTestFixture.newMariaDbContainer();
        mariaDbContainer.start();
        mariaDatabase = DatabaseTestFixture.connectToContainer(mariaDbContainer, Dialect.MYSQL);
        mariaRunner = new MigrationRunner(mariaDatabase);

        postgresContainer = DatabaseTestFixture.newPostgresContainer();
        postgresContainer.start();
        postgresDatabase = DatabaseTestFixture.connectToContainer(postgresContainer, Dialect.POSTGRES);
        postgresRunner = new MigrationRunner(postgresDatabase);
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

    // ==========================================
    // MariaDB Integration Tests
    // ==========================================

    @Test
    @Order(1)
    @DisplayName("MariaDB 1: Clean database migrates to V1 then upgrades to V2 cleanly")
    void mariaDbMigratesCleanDatabase() throws Exception {
        assertThat(mariaRunner.currentVersion()).isEqualTo(0);

        // 1. Apply only V1 migration
        List<Migration> allMigrations = SkyblockMigrations.getMigrations(mariaDatabase.dialect());
        Migration v1 = allMigrations.get(0);
        int v1Applied = mariaRunner.apply(List.of(v1));
        assertThat(v1Applied).isEqualTo(1);
        assertThat(mariaRunner.currentVersion()).isEqualTo(1);

        // 2. Verify pre-V2 schema state: profile_inventories does NOT exist
        try (Connection conn = mariaDatabase.connection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getTables(null, null, "profile_inventories", new String[] {"TABLE"})) {
                assertThat(rs.next()).isFalse();
            }
        }

        // 3. Apply V1 + V2 to upgrade to V2
        int v2Applied = mariaRunner.apply(allMigrations.subList(0, 2));
        assertThat(v2Applied).isEqualTo(1);
        assertThat(mariaRunner.currentVersion()).isEqualTo(2);

        // 4. Verify V2 inventory schema: profile_inventories now exists, journals do not
        try (Connection conn = mariaDatabase.connection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getTables(null, null, "profile_inventories", new String[] {"TABLE"})) {
                assertThat(rs.next()).isTrue();
            }
            try (ResultSet rs = meta.getTables(null, null, "inventory_mutation_journals", new String[] {"TABLE"})) {
                assertThat(rs.next()).isFalse();
            }
        }

        // 5. Apply V3 migration
        int v3Applied = mariaRunner.apply(allMigrations.subList(0, 3));
        assertThat(v3Applied).isEqualTo(1);
        assertThat(mariaRunner.currentVersion()).isEqualTo(3);

        // 6. Verify V3 schema: journals and participants now exist
        try (Connection conn = mariaDatabase.connection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getTables(null, null, "inventory_mutation_journals", new String[] {"TABLE"})) {
                assertThat(rs.next()).isTrue();
            }
            try (ResultSet rs = meta.getTables(null, null, "inventory_mutation_participants", new String[] {"TABLE"})) {
                assertThat(rs.next()).isTrue();
            }
        }

        // 7. Apply V4 migration
        int v4Applied = mariaRunner.apply(allMigrations.subList(0, 4));
        assertThat(v4Applied).isEqualTo(1);
        assertThat(mariaRunner.currentVersion()).isEqualTo(4);

        // 8. Verify V4 schema: profile_switch_operations now exists
        try (Connection conn = mariaDatabase.connection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getTables(null, null, "profile_switch_operations", new String[] {"TABLE"})) {
                assertThat(rs.next()).isTrue();
            }
        }

        // 9. Apply migration V5
        int v5Applied = mariaRunner.apply(allMigrations.subList(0, 5));
        assertThat(v5Applied).isEqualTo(1);
        assertThat(mariaRunner.currentVersion()).isEqualTo(5);

        // 10. Verify V5 schema: islands now exists
        try (Connection conn = mariaDatabase.connection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getTables(null, null, "islands", new String[] {"TABLE"})) {
                assertThat(rs.next()).isTrue();
            }
        }

        // 11. Apply V6 migration
        int v6Applied = mariaRunner.apply(allMigrations.subList(0, 6));
        assertThat(v6Applied).isEqualTo(1);
        assertThat(mariaRunner.currentVersion()).isEqualTo(6);

        // 12. Verify V6 schema: island_banks now exists
        try (Connection conn = mariaDatabase.connection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getTables(null, null, "island_banks", new String[] {"TABLE"})) {
                assertThat(rs.next()).isTrue();
            }
        }

        // 13. Apply migrations up to V7
        int v7Applied = mariaRunner.apply(allMigrations.subList(0, 7));
        assertThat(v7Applied).isEqualTo(1);
        assertThat(mariaRunner.currentVersion()).isEqualTo(7);

        // 14. Verify V7 schema: island_upgrades now exists
        try (Connection conn = mariaDatabase.connection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getTables(null, null, "island_upgrades", new String[] {"TABLE"})) {
                assertThat(rs.next()).isTrue();
            }
        }

        // 15. Apply V8 migration
        int v8Applied = mariaRunner.apply(allMigrations.subList(0, 8));
        assertThat(v8Applied).isEqualTo(1);
        assertThat(mariaRunner.currentVersion()).isEqualTo(8);

        // 16. Verify V8 schema: backup_operations now exists
        try (Connection conn = mariaDatabase.connection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getTables(null, null, "backup_operations", new String[] {"TABLE"})) {
                assertThat(rs.next()).isTrue();
            }
        }

        // 17. Apply all migrations to upgrade to V9
        int v9Applied = mariaRunner.apply(allMigrations);
        assertThat(v9Applied).isEqualTo(1);
        assertThat(mariaRunner.currentVersion()).isEqualTo(SkyblockMigrations.LATEST_VERSION);

        // 18. Verify V9 schema: outbox_events and consumer_inbox now exist
        try (Connection conn = mariaDatabase.connection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getTables(null, null, "outbox_events", new String[] {"TABLE"})) {
                assertThat(rs.next()).isTrue();
            }
            try (ResultSet rs = meta.getTables(null, null, "consumer_inbox", new String[] {"TABLE"})) {
                assertThat(rs.next()).isTrue();
            }
        }
    }

    @Test
    @Order(2)
    @DisplayName("MariaDB 2: Idempotent migration rerun applies zero migrations")
    void mariaDbIdempotentRerun() {
        int rerun = mariaRunner.apply(SkyblockMigrations.getMigrations(mariaDatabase.dialect()));
        assertThat(rerun).isEqualTo(0);
        assertThat(mariaRunner.currentVersion()).isEqualTo(SkyblockMigrations.LATEST_VERSION);
    }

    @Test
    @Order(3)
    @DisplayName("MariaDB 3: Verified tables exist and deferred/future tables are absent")
    void mariaDbVerifiesTables() throws Exception {
        verifyCanonicalTables(mariaDatabase);
    }

    @Test
    @Order(4)
    @DisplayName("MariaDB 4: Verified columns exist on canonical foundation tables")
    void mariaDbVerifiesColumns() throws Exception {
        verifyCanonicalColumns(mariaDatabase);
    }

    @Test
    @Order(5)
    @DisplayName("MariaDB 5: Non-circular lifecycle, composite FK, and cascade delete")
    void mariaDbVerifiesLifecycleAndConstraints() throws Exception {
        verifyLifecycleAndConstraints(mariaDatabase, "maria");
    }

    // ==========================================
    // PostgreSQL Integration Tests
    // ==========================================

    @Test
    @Order(6)
    @DisplayName("PostgreSQL 1: Clean database migrates to V1 then upgrades to V2 cleanly")
    void postgresMigratesCleanDatabase() throws Exception {
        assertThat(postgresRunner.currentVersion()).isEqualTo(0);

        // 1. Apply only V1 migration
        List<Migration> allMigrations = SkyblockMigrations.getMigrations(postgresDatabase.dialect());
        Migration v1 = allMigrations.get(0);
        int v1Applied = postgresRunner.apply(List.of(v1));
        assertThat(v1Applied).isEqualTo(1);
        assertThat(postgresRunner.currentVersion()).isEqualTo(1);

        // 2. Verify pre-V2 schema state: profile_inventories does NOT exist
        try (Connection conn = postgresDatabase.connection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getTables(null, null, "profile_inventories", new String[] {"TABLE"})) {
                assertThat(rs.next()).isFalse();
            }
        }

        // 3. Apply V1 + V2 to upgrade to V2
        int v2Applied = postgresRunner.apply(allMigrations.subList(0, 2));
        assertThat(v2Applied).isEqualTo(1);
        assertThat(postgresRunner.currentVersion()).isEqualTo(2);

        // 4. Verify V2 inventory schema: profile_inventories now exists, journals do not
        try (Connection conn = postgresDatabase.connection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getTables(null, null, "profile_inventories", new String[] {"TABLE"})) {
                assertThat(rs.next()).isTrue();
            }
            try (ResultSet rs = meta.getTables(null, null, "inventory_mutation_journals", new String[] {"TABLE"})) {
                assertThat(rs.next()).isFalse();
            }
        }

        // 5. Apply V3 migration
        int v3Applied = postgresRunner.apply(allMigrations.subList(0, 3));
        assertThat(v3Applied).isEqualTo(1);
        assertThat(postgresRunner.currentVersion()).isEqualTo(3);

        // 6. Verify V3 schema: journals and participants now exist
        try (Connection conn = postgresDatabase.connection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getTables(null, null, "inventory_mutation_journals", new String[] {"TABLE"})) {
                assertThat(rs.next()).isTrue();
            }
            try (ResultSet rs = meta.getTables(null, null, "inventory_mutation_participants", new String[] {"TABLE"})) {
                assertThat(rs.next()).isTrue();
            }
        }

        // 7. Apply V4 migration
        int v4Applied = postgresRunner.apply(allMigrations.subList(0, 4));
        assertThat(v4Applied).isEqualTo(1);
        assertThat(postgresRunner.currentVersion()).isEqualTo(4);

        // 8. Verify V4 schema: profile_switch_operations now exists
        try (Connection conn = postgresDatabase.connection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getTables(null, null, "profile_switch_operations", new String[] {"TABLE"})) {
                assertThat(rs.next()).isTrue();
            }
        }

        // 9. Apply migration V5
        int v5Applied = postgresRunner.apply(allMigrations.subList(0, 5));
        assertThat(v5Applied).isEqualTo(1);
        assertThat(postgresRunner.currentVersion()).isEqualTo(5);

        // 10. Verify V5 schema: islands now exists
        try (Connection conn = postgresDatabase.connection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getTables(null, null, "islands", new String[] {"TABLE"})) {
                assertThat(rs.next()).isTrue();
            }
        }

        // 11. Apply V6 migration
        int v6Applied = postgresRunner.apply(allMigrations.subList(0, 6));
        assertThat(v6Applied).isEqualTo(1);
        assertThat(postgresRunner.currentVersion()).isEqualTo(6);

        // 12. Verify V6 schema: island_banks now exists
        try (Connection conn = postgresDatabase.connection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getTables(null, null, "island_banks", new String[] {"TABLE"})) {
                assertThat(rs.next()).isTrue();
            }
        }

        // 13. Apply migrations up to V7
        int v7Applied = postgresRunner.apply(allMigrations.subList(0, 7));
        assertThat(v7Applied).isEqualTo(1);
        assertThat(postgresRunner.currentVersion()).isEqualTo(7);

        // 14. Verify V7 schema: island_upgrades now exists
        try (Connection conn = postgresDatabase.connection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getTables(null, null, "island_upgrades", new String[] {"TABLE"})) {
                assertThat(rs.next()).isTrue();
            }
        }

        // 15. Apply V8 migration
        int v8Applied = postgresRunner.apply(allMigrations.subList(0, 8));
        assertThat(v8Applied).isEqualTo(1);
        assertThat(postgresRunner.currentVersion()).isEqualTo(8);

        // 16. Verify V8 schema: backup_operations now exists
        try (Connection conn = postgresDatabase.connection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getTables(null, null, "backup_operations", new String[] {"TABLE"})) {
                assertThat(rs.next()).isTrue();
            }
        }

        // 17. Apply all migrations to upgrade to V9
        int v9Applied = postgresRunner.apply(allMigrations);
        assertThat(v9Applied).isEqualTo(1);
        assertThat(postgresRunner.currentVersion()).isEqualTo(SkyblockMigrations.LATEST_VERSION);

        // 18. Verify V9 schema: outbox_events and consumer_inbox now exist
        try (Connection conn = postgresDatabase.connection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getTables(null, null, "outbox_events", new String[] {"TABLE"})) {
                assertThat(rs.next()).isTrue();
            }
            try (ResultSet rs = meta.getTables(null, null, "consumer_inbox", new String[] {"TABLE"})) {
                assertThat(rs.next()).isTrue();
            }
        }
    }

    @Test
    @Order(7)
    @DisplayName("PostgreSQL 2: Idempotent migration rerun applies zero migrations")
    void postgresIdempotentRerun() {
        int rerun = postgresRunner.apply(SkyblockMigrations.getMigrations(postgresDatabase.dialect()));
        assertThat(rerun).isEqualTo(0);
        assertThat(postgresRunner.currentVersion()).isEqualTo(SkyblockMigrations.LATEST_VERSION);
    }

    @Test
    @Order(8)
    @DisplayName("PostgreSQL 3: Verified tables exist and deferred/future tables are absent")
    void postgresVerifiesTables() throws Exception {
        verifyCanonicalTables(postgresDatabase);
    }

    @Test
    @Order(9)
    @DisplayName("PostgreSQL 4: Verified columns exist on canonical foundation tables")
    void postgresVerifiesColumns() throws Exception {
        verifyCanonicalColumns(postgresDatabase);
    }

    @Test
    @Order(10)
    @DisplayName("PostgreSQL 5: Non-circular lifecycle, composite FK, and cascade delete")
    void postgresVerifiesLifecycleAndConstraints() throws Exception {
        verifyLifecycleAndConstraints(postgresDatabase, "pg");
    }

    // ==========================================
    // Shared Verification Logic
    // ==========================================

    private static void verifyCanonicalTables(Database db) throws Exception {
        try (Connection conn = db.connection()) {
            DatabaseMetaData meta = conn.getMetaData();
            Set<String> tables = new HashSet<>();
            try (ResultSet rs = meta.getTables(null, null, "%", new String[] {"TABLE"})) {
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
                }
            }

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
                            "uxmlib_schema_history");

            assertThat(tables).doesNotContain("inbox_events");
        }
    }

    private static void verifyCanonicalColumns(Database db) throws Exception {
        try (Connection conn = db.connection()) {
            DatabaseMetaData meta = conn.getMetaData();

            Set<String> accountCols = getColumnNames(meta, "player_accounts");
            assertThat(accountCols)
                    .contains(
                            "player_uuid",
                            "active_profile_id",
                            "active_switch_operation_id",
                            "created_at",
                            "updated_at");

            Set<String> profileCols = getColumnNames(meta, "player_profiles");
            assertThat(profileCols).contains("profile_id", "player_uuid", "profile_type", "created_at");

            Set<String> sessionCols = getColumnNames(meta, "player_sessions");
            assertThat(sessionCols)
                    .contains(
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

            Set<String> inventoryCols = getColumnNames(meta, "profile_inventories");
            assertThat(inventoryCols)
                    .contains(
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

            Set<String> journalCols = getColumnNames(meta, "inventory_mutation_journals");
            assertThat(journalCols)
                    .contains(
                            "operation_id",
                            "operation_type",
                            "state",
                            "participant_count",
                            "payload",
                            "expires_at",
                            "created_at",
                            "updated_at");

            Set<String> participantCols = getColumnNames(meta, "inventory_mutation_participants");
            assertThat(participantCols)
                    .contains(
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

            Set<String> switchCols = getColumnNames(meta, "profile_switch_operations");
            assertThat(switchCols)
                    .contains(
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

            Set<String> islandCols = getColumnNames(meta, "islands");
            assertThat(islandCols)
                    .contains(
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

            Set<String> authCols = getColumnNames(meta, "island_authorities");
            assertThat(authCols)
                    .contains(
                            "island_id",
                            "authoritative_node",
                            "authority_epoch",
                            "lease_expires_at",
                            "last_heartbeat_at",
                            "updated_at");

            Set<String> locCols = getColumnNames(meta, "island_locations");
            assertThat(locCols)
                    .contains(
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

            Set<String> memCols = getColumnNames(meta, "island_members");
            assertThat(memCols).contains("island_id", "player_uuid", "profile_id", "role_id", "joined_at");

            Set<String> roleCols = getColumnNames(meta, "island_roles");
            assertThat(roleCols).contains("island_id", "role_id", "weight", "display_name", "is_system");

            Set<String> permCols = getColumnNames(meta, "island_role_permissions");
            assertThat(permCols).contains("island_id", "role_id", "permission");

            Set<String> flagCols = getColumnNames(meta, "island_flags");
            assertThat(flagCols).contains("island_id", "flag_name", "flag_value");

            Set<String> bankCols = getColumnNames(meta, "island_banks");
            assertThat(bankCols)
                    .contains(
                            "island_id",
                            "primary_balance_minor_units",
                            "crystals_balance",
                            "exp_balance",
                            "version",
                            "updated_at");

            Set<String> txCols = getColumnNames(meta, "bank_transactions");
            assertThat(txCols)
                    .contains(
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

            Set<String> opCols = getColumnNames(meta, "processed_operations");
            assertThat(opCols)
                    .contains(
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

            Set<String> upgCols = getColumnNames(meta, "island_upgrades");
            assertThat(upgCols).contains("island_id", "upgrade_key", "tier", "updated_at");

            Set<String> bakCols = getColumnNames(meta, "backup_operations");
            assertThat(bakCols)
                    .contains(
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

            Set<String> outboxCols = getColumnNames(meta, "outbox_events");
            assertThat(outboxCols)
                    .contains(
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

            Set<String> inboxCols = getColumnNames(meta, "consumer_inbox");
            assertThat(inboxCols).contains("consumer_name", "event_id", "processed_at");
        }
    }

    private static void verifyLifecycleAndConstraints(Database db, String prefix) throws Exception {
        String p1 = prefix + "-p1";
        String prof1 = prefix + "-prof1";
        String p2 = prefix + "-p2";
        String prof2 = prefix + "-prof2";

        try (Connection conn = db.connection()) {
            // 1. Initial non-circular account creation with null active profile
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO player_accounts (player_uuid, active_profile_id) VALUES (?, NULL)")) {
                stmt.setString(1, p1);
                stmt.executeUpdate();
            }

            // 2. Profile creation for p1
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO player_profiles (profile_id, player_uuid, profile_type) VALUES (?, ?, 'CLASSIC')")) {
                stmt.setString(1, prof1);
                stmt.setString(2, p1);
                stmt.executeUpdate();
            }

            // 3. Update account active profile
            try (PreparedStatement stmt =
                    conn.prepareStatement("UPDATE player_accounts SET active_profile_id = ? WHERE player_uuid = ?")) {
                stmt.setString(1, prof1);
                stmt.setString(2, p1);
                stmt.executeUpdate();
            }

            // 4. Create player session
            try (PreparedStatement stmt = conn.prepareStatement("""
                    INSERT INTO player_sessions (
                        player_uuid, active_profile_id, authoritative_node, session_epoch,
                        state, last_durable_inventory_version, lease_expires_at
                    ) VALUES (?, ?, ?, 1, 'ACTIVE', 1, CURRENT_TIMESTAMP)
                    """)) {
                stmt.setString(1, p1);
                stmt.setString(2, prof1);
                stmt.setString(3, "node-server");
                stmt.executeUpdate();
            }

            // 5. Create profile inventory
            try (PreparedStatement stmt = conn.prepareStatement("""
                    INSERT INTO profile_inventories (profile_id, inventory_nbt, enderchest_nbt)
                    VALUES (?, ?, ?)
                    """)) {
                stmt.setString(1, prof1);
                stmt.setBytes(2, new byte[] {1, 2});
                stmt.setBytes(3, new byte[] {3, 4});
                stmt.executeUpdate();
            }

            // 6. Account 2 & Profile 2
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO player_accounts (player_uuid, active_profile_id) VALUES (?, NULL)")) {
                stmt.setString(1, p2);
                stmt.executeUpdate();
            }
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO player_profiles (profile_id, player_uuid, profile_type) VALUES (?, ?, 'CLASSIC')")) {
                stmt.setString(1, prof2);
                stmt.setString(2, p2);
                stmt.executeUpdate();
            }

            // 7. Cross-tenant composite FK violation attempt on player_accounts
            assertThatThrownBy(() -> {
                        try (PreparedStatement stmt = conn.prepareStatement(
                                "UPDATE player_accounts SET active_profile_id = ? WHERE player_uuid = ?")) {
                            stmt.setString(1, prof2);
                            stmt.setString(2, p1);
                            stmt.executeUpdate();
                        }
                    })
                    .isInstanceOf(SQLException.class);

            // 8. Cross-tenant composite FK violation attempt on player_sessions
            assertThatThrownBy(() -> {
                        try (PreparedStatement stmt = conn.prepareStatement("""
                        INSERT INTO player_sessions (
                            player_uuid, active_profile_id, authoritative_node, session_epoch,
                            state, last_durable_inventory_version, lease_expires_at
                        ) VALUES (?, ?, 'bad-node', 1, 'ACTIVE', 1, CURRENT_TIMESTAMP)
                        """)) {
                            stmt.setString(1, p1);
                            stmt.setString(2, prof2);
                            stmt.executeUpdate();
                        }
                    })
                    .isInstanceOf(SQLException.class);

            // 9. Terminate active session, break circular reference, then delete account
            try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM player_sessions WHERE player_uuid = ?")) {
                stmt.setString(1, p1);
                stmt.executeUpdate();
            }
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE player_accounts SET active_profile_id = NULL WHERE player_uuid = ?")) {
                stmt.setString(1, p1);
                stmt.executeUpdate();
            }
            try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM player_accounts WHERE player_uuid = ?")) {
                stmt.setString(1, p1);
                stmt.executeUpdate();
            }

            // Confirm profiles and inventory are deleted by cascade
            assertThat(queryCount(conn, "SELECT COUNT(*) FROM player_profiles WHERE player_uuid = '" + p1 + "'"))
                    .isEqualTo(0);
            assertThat(queryCount(conn, "SELECT COUNT(*) FROM player_sessions WHERE player_uuid = '" + p1 + "'"))
                    .isEqualTo(0);
            assertThat(queryCount(conn, "SELECT COUNT(*) FROM profile_inventories WHERE profile_id = '" + prof1 + "'"))
                    .isEqualTo(0);
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
