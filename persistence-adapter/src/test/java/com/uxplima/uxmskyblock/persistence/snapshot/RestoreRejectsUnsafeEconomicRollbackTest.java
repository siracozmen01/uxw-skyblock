package com.uxplima.uxmskyblock.persistence.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeInstanceId;
import com.uxplima.uxmskyblock.core.domain.gamemode.PrimaryGameplayRootRef;
import com.uxplima.uxmskyblock.core.domain.snapshot.RestoreMode;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * A world snapshot never winds the economy back.
 *
 * <p>The persistence specification says even the widest mode leaves the bank balance, the
 * transaction history, the vault pages and the players' inventories where they are. The relational
 * restore used to delete the island's own row to write the old one back, and every one of those
 * tables hangs off that row with a cascading key. On an engine that enforces the key the restore took
 * the bank, its history and the vault with it and put back nothing, and on PostgreSQL it did not run
 * at all.
 */
class RestoreRejectsUnsafeEconomicRollbackTest {

    private Database database;

    @BeforeEach
    void setUp() {
        database = DatabaseTestFixture.createSqliteInMemory();
        new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
    }

    @AfterEach
    void tearDown() {
        if (database != null && !database.isClosed()) {
            database.close();
        }
    }

    @ParameterizedTest
    @EnumSource(RestoreMode.class)
    @DisplayName("A restore leaves the bank, its history, the vault and the inventories as they are now")
    void theEconomyStaysWhereItIs(RestoreMode mode) throws Exception {
        restoreLeavesTheEconomyWhereItIs(database, mode, true);
    }

    /**
     * Snapshots an island, moves its money and items on, restores the snapshot and reads everything
     * back. {@code enforceKeys} turns SQLite's foreign keys on for the connection the restore uses,
     * which is what MariaDB and PostgreSQL always do.
     */
    static void restoreLeavesTheEconomyWhereItIs(Database database, RestoreMode mode, boolean enforceKeys)
            throws SQLException {
        String islandId = UUID.randomUUID().toString();
        String owner = UUID.randomUUID().toString();
        String ownerProfile = UUID.randomUUID().toString();
        String newcomer = UUID.randomUUID().toString();
        String newcomerProfile = UUID.randomUUID().toString();

        try (Connection conn = database.connection()) {
            if (enforceKeys) {
                enforceForeignKeys(conn);
            }
            update(conn, "INSERT INTO player_accounts (player_uuid) VALUES (?)", owner);
            update(conn, "INSERT INTO player_profiles (profile_id, player_uuid) VALUES (?, ?)", ownerProfile, owner);
            update(
                    conn,
                    "INSERT INTO profile_inventories (profile_id, inventory_nbt, enderchest_nbt) VALUES (?, ?, ?)",
                    ownerProfile,
                    bytes("inventory-then"),
                    bytes("ender-then"));
            update(
                    conn,
                    "INSERT INTO islands (id, owner_profile_id, owner_account_uuid) VALUES (?, ?, ?)",
                    islandId,
                    ownerProfile,
                    owner);
            update(
                    conn,
                    "INSERT INTO island_members (island_id, player_uuid, profile_id, role_id) VALUES (?, ?, ?, ?)",
                    islandId,
                    owner,
                    ownerProfile,
                    "OWNER");
            update(
                    conn,
                    "INSERT INTO island_banks (island_id, primary_balance_minor_units, version) VALUES (?, ?, ?)",
                    islandId,
                    1_000L,
                    1L);
            transaction(conn, islandId, owner, 1_000L, 1_000L);
            update(
                    conn,
                    "INSERT INTO island_vault_pages (island_id, page, contents_nbt, last_modified_by)"
                            + " VALUES (?, ?, ?, ?)",
                    islandId,
                    1,
                    bytes("vault-then"),
                    owner);
        }

        SqlRootRelationalSnapshotAdapter adapter = new SqlRootRelationalSnapshotAdapter(database.dataSource());
        PrimaryGameplayRootRef root =
                new PrimaryGameplayRootRef(GameModeInstanceId.of(UUID.randomUUID()), islandId, "ISLAND", Instant.now());
        byte[] snapshot = adapter.captureRelationalSnapshot(root, 1L);

        // After the snapshot the island earns, the owner moves diamonds into the vault and plays on,
        // and a second player joins.
        try (Connection conn = database.connection()) {
            update(
                    conn,
                    "UPDATE island_banks SET primary_balance_minor_units = ?, version = ? WHERE island_id = ?",
                    5_000L,
                    2L,
                    islandId);
            transaction(conn, islandId, owner, 4_000L, 5_000L);
            update(
                    conn,
                    "UPDATE island_vault_pages SET contents_nbt = ?, page_version = ? WHERE island_id = ? AND page = ?",
                    bytes("vault-now"),
                    2L,
                    islandId,
                    1);
            update(
                    conn,
                    "UPDATE profile_inventories SET inventory_nbt = ?, profile_inventory_version = ? WHERE profile_id = ?",
                    bytes("inventory-now"),
                    2L,
                    ownerProfile);
            update(
                    conn,
                    "INSERT INTO island_members (island_id, player_uuid, profile_id, role_id) VALUES (?, ?, ?, ?)",
                    islandId,
                    newcomer,
                    newcomerProfile,
                    "MEMBER");
        }

        adapter.restoreRelationalSnapshot(root, snapshot, mode);

        try (Connection conn = database.connection()) {
            assertThat(longs(
                            conn, "SELECT primary_balance_minor_units FROM island_banks WHERE island_id = ?", islandId))
                    .describedAs("the bank after a %s restore", mode)
                    .containsExactly(5_000L);
            assertThat(longs(
                            conn,
                            "SELECT resulting_balance_minor_units FROM bank_transactions WHERE island_id = ?"
                                    + " ORDER BY resulting_balance_minor_units",
                            islandId))
                    .describedAs("the bank history after a %s restore", mode)
                    .containsExactly(1_000L, 5_000L);
            assertThat(text(
                            conn,
                            "SELECT contents_nbt FROM island_vault_pages WHERE island_id = ? AND page = 1",
                            islandId))
                    .describedAs("the vault after a %s restore", mode)
                    .containsExactly("vault-now");
            assertThat(text(conn, "SELECT inventory_nbt FROM profile_inventories WHERE profile_id = ?", ownerProfile))
                    .describedAs("the inventory after a %s restore", mode)
                    .containsExactly("inventory-now");
            assertThat(longs(conn, "SELECT COUNT(*) FROM islands WHERE id = ?", islandId))
                    .describedAs("the island after a %s restore", mode)
                    .containsExactly(1L);
            assertThat(longs(conn, "SELECT COUNT(*) FROM island_members WHERE island_id = ?", islandId))
                    .describedAs("the members after a %s restore", mode)
                    .containsExactly(mode.restoresMembership() ? 1L : 2L);
        }
    }

    private static void enforceForeignKeys(Connection conn) throws SQLException {
        if (conn.getMetaData()
                .getDatabaseProductName()
                .toLowerCase(java.util.Locale.ROOT)
                .contains("sqlite")) {
            try (var stmt = conn.createStatement()) {
                stmt.execute("PRAGMA foreign_keys = ON");
            }
        }
    }

    private static void transaction(Connection conn, String islandId, String actor, long delta, long resulting)
            throws SQLException {
        update(
                conn,
                "INSERT INTO bank_transactions (transaction_id, operation_id, island_id, actor_uuid,"
                        + " delta_amount_minor_units, resulting_balance_minor_units, reason)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                islandId,
                actor,
                delta,
                resulting,
                "DEPOSIT");
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static void update(Connection conn, String sql, Object... values) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                if (values[i] instanceof byte[] raw) {
                    stmt.setBytes(i + 1, raw);
                } else {
                    stmt.setObject(i + 1, values[i]);
                }
            }
            stmt.executeUpdate();
        }
    }

    private static List<Long> longs(Connection conn, String sql, String key) throws SQLException {
        List<Long> found = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, key);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    found.add(rs.getLong(1));
                }
            }
        }
        return found;
    }

    private static List<String> text(Connection conn, String sql, String key) throws SQLException {
        List<String> found = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, key);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    found.add(new String(rs.getBytes(1), StandardCharsets.UTF_8));
                }
            }
        }
        return found;
    }
}
