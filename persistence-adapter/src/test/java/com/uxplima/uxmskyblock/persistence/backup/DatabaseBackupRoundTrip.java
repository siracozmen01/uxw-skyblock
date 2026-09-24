package com.uxplima.uxmskyblock.persistence.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.backup.DatabaseBackupDialect;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryRecord;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.session.SessionAuthorityOutcome;
import com.uxplima.uxmskyblock.persistence.inventory.PlayerProfileInventoryAdapter;
import com.uxplima.uxmskyblock.persistence.session.PlayerSessionAuthorityAdapter;

/**
 * A whole-database backup taken, the data changed, the backup put back: every row as it was.
 *
 * <p>Rows are chosen for what broke: an inventory in binary, a flag that is a boolean, a lease that
 * is a timestamp, and an island name holding a quote, a backslash and a line that ends in a semicolon.
 */
final class DatabaseBackupRoundTrip {

    private static final byte[] INVENTORY = {0, 1, 2, 127, -128, -1, 10, 59, 39, 92};
    private static final String AWKWARD_NAME = "O'Neil \\\\ dock;\nnext line";

    private final Database database;
    private final DatabaseBackupDialect dialect;
    private final SqlDatabaseBackupAdapter backups;
    private final PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
    private final ProfileId profile = ProfileId.of(UUID.randomUUID());
    private final String island = UUID.randomUUID().toString();

    DatabaseBackupRoundTrip(Database database, DatabaseBackupDialect dialect) {
        this.database = database;
        this.dialect = dialect;
        this.backups = new SqlDatabaseBackupAdapter(database);
    }

    /** Takes a backup, changes and deletes rows, puts the backup back, and reads every row as it was. */
    void everyRowComesBack() throws Exception {
        long epoch = ((SessionAuthorityOutcome.Success) new PlayerSessionAuthorityAdapter(database)
                        .ensureSession(player, profile, ServerNodeId.of("node-a")))
                .epoch();
        PlayerProfileInventoryAdapter inventories = new PlayerProfileInventoryAdapter(database);
        long version = inventories.loadInventory(profile).orElseThrow().version();
        assertThat(inventories
                        .checkpointInventory(
                                player,
                                profile,
                                ServerNodeId.of("node-a"),
                                epoch,
                                version,
                                new ProfileInventoryRecord(
                                        profile,
                                        version,
                                        INVENTORY,
                                        new byte[] {9},
                                        55,
                                        12.5,
                                        18,
                                        4.5f,
                                        new byte[] {3},
                                        "world",
                                        1.5,
                                        64.0,
                                        -2.25,
                                        "ADVENTURE",
                                        true))
                        .isSuccess())
                .isTrue();
        execute(
                "INSERT INTO islands (id, owner_profile_id, owner_account_uuid, custom_name, lifecycle,"
                        + " economic_state, administrative_state, level_score, net_worth_minor_units, version)"
                        + " VALUES (?, ?, ?, ?, 'ACTIVE', 'NORMAL', 'NORMAL', 7, 123456789012, 1)",
                island,
                profile.value().toString(),
                player.value().toString(),
                AWKWARD_NAME);
        ProfileInventoryRecord before = inventories.loadInventory(profile).orElseThrow();
        String leaseBefore = lease();

        byte[] backup = backups.captureDatabaseBackup(dialect);
        assertThat(new String(backup, StandardCharsets.UTF_8))
                .describedAs("a backup carries no credential")
                .doesNotContainIgnoringCase("password");

        execute(
                "DELETE FROM player_sessions WHERE player_uuid = ?",
                player.value().toString());
        execute("UPDATE islands SET custom_name = 'changed' WHERE id = ?", island);
        execute(
                "UPDATE profile_inventories SET inventory_nbt = ? WHERE profile_id = ?",
                new byte[] {42},
                profile.value().toString());

        backups.restoreDatabaseBackup(backup, dialect, true);

        ProfileInventoryRecord after = inventories.loadInventory(profile).orElseThrow();
        assertThat(after.inventoryNbt()).isEqualTo(INVENTORY);
        assertThat(after.enderchestNbt()).isEqualTo(before.enderchestNbt());
        assertThat(after.activePotionEffectsNbt()).isEqualTo(before.activePotionEffectsNbt());
        assertThat(after.flightAllowed()).isTrue();
        assertThat(after.saturation()).isEqualTo(4.5f);
        assertThat(after.logoutZ()).isEqualTo(-2.25);
        assertThat(after.version()).isEqualTo(before.version());
        assertThat(islandName()).isEqualTo(AWKWARD_NAME);
        assertThat(lease()).isEqualTo(leaseBefore);
    }

    /** A backup is put back only into the dialect that took it, only when confirmed, only if it is one. */
    void itFailsClosed() {
        byte[] backup = backups.captureDatabaseBackup(dialect);
        DatabaseBackupDialect other = dialect == DatabaseBackupDialect.SQLITE
                ? DatabaseBackupDialect.POSTGRESQL
                : DatabaseBackupDialect.SQLITE;

        assertThatThrownBy(() -> backups.captureDatabaseBackup(other)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> backups.restoreDatabaseBackup(backup, dialect, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        backups.restoreDatabaseBackup("not a backup".getBytes(StandardCharsets.UTF_8), dialect, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private String lease() throws SQLException {
        return single(
                "SELECT lease_expires_at FROM player_sessions WHERE player_uuid = ?",
                player.value().toString());
    }

    private String islandName() throws SQLException {
        return single("SELECT custom_name FROM islands WHERE id = ?", island);
    }

    private String single(String sql, String key) throws SQLException {
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).describedAs(sql).isTrue();
                return String.valueOf(rs.getObject(1));
            }
        }
    }

    private void execute(String sql, Object... values) throws SQLException {
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                ps.setObject(i + 1, values[i]);
            }
            ps.executeUpdate();
        }
    }
}
