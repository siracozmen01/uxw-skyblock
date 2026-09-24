package com.uxplima.uxmskyblock.persistence.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
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
import org.junit.jupiter.api.Test;

/**
 * One island's snapshot holds that island and nothing else, and putting it back touches nothing else.
 *
 * <p>The game mode architecture names this test for {@code RootStateSnapshotPort}; the relational half
 * of a root's state is captured and restored by the relational snapshot adapter, and the rule is the
 * same. The snapshot of one island names no other island and no other island's players, and restoring
 * it leaves every other island and the server's own records exactly as they are.
 */
class RootStateSnapshotIsolationTest {

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

    @Test
    @DisplayName("A snapshot holds only its island, and restoring it leaves the neighbour and the server alone")
    void oneIslandsSnapshotIsItsOwn() throws Exception {
        String alpha = UUID.randomUUID().toString();
        String beta = UUID.randomUUID().toString();
        String betaOwner = UUID.randomUUID().toString();
        try (Connection conn = database.connection()) {
            island(conn, alpha, UUID.randomUUID().toString());
            island(conn, beta, betaOwner);
            update(
                    conn,
                    "INSERT INTO player_anti_abuse_records (player_uuid, resets_today_count) VALUES (?, 2)",
                    betaOwner);
        }

        SqlRootRelationalSnapshotAdapter adapter = new SqlRootRelationalSnapshotAdapter(database.dataSource());
        PrimaryGameplayRootRef root =
                new PrimaryGameplayRootRef(GameModeInstanceId.of(UUID.randomUUID()), alpha, "ISLAND", Instant.now());
        byte[] snapshot = adapter.captureRelationalSnapshot(root, 1L);

        String held = new String(snapshot, StandardCharsets.UTF_8);
        assertThat(held).contains(alpha).doesNotContain(beta).doesNotContain(betaOwner);

        try (Connection conn = database.connection()) {
            update(conn, "UPDATE island_flags SET flag_value = ? WHERE flag_name = 'PVP'", true);
        }
        adapter.restoreRelationalSnapshot(root, snapshot, RestoreMode.FULL_ISLAND);

        assertThat(pvp(alpha))
                .describedAs("the island the snapshot was of, put back")
                .isFalse();
        assertThat(pvp(beta)).describedAs("the neighbour, as it is now").isTrue();
        assertThat(count("SELECT COUNT(*) FROM island_members WHERE island_id = '" + beta + "'"))
                .isEqualTo(1);
        assertThat(count("SELECT resets_today_count FROM player_anti_abuse_records WHERE player_uuid = '" + betaOwner
                        + "'"))
                .describedAs("the server's own record of a player")
                .isEqualTo(2);
    }

    private static void island(Connection conn, String islandId, String owner) throws SQLException {
        String profile = UUID.randomUUID().toString();
        update(conn, "INSERT INTO player_accounts (player_uuid) VALUES (?)", owner);
        update(conn, "INSERT INTO player_profiles (profile_id, player_uuid) VALUES (?, ?)", profile, owner);
        update(
                conn,
                "INSERT INTO islands (id, owner_profile_id, owner_account_uuid) VALUES (?, ?, ?)",
                islandId,
                profile,
                owner);
        update(
                conn,
                "INSERT INTO island_members (island_id, player_uuid, profile_id, role_id) VALUES (?, ?, ?, ?)",
                islandId,
                owner,
                profile,
                "OWNER");
        update(
                conn,
                "INSERT INTO island_flags (island_id, flag_name, flag_value) VALUES (?, 'PVP', ?)",
                islandId,
                false);
    }

    private boolean pvp(String islandId) throws SQLException {
        return count("SELECT flag_value FROM island_flags WHERE flag_name = 'PVP' AND island_id = '" + islandId + "'")
                == 1;
    }

    private int count(String sql) throws SQLException {
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static void update(Connection conn, String sql, Object... values) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                stmt.setObject(i + 1, values[i]);
            }
            stmt.executeUpdate();
        }
    }
}
