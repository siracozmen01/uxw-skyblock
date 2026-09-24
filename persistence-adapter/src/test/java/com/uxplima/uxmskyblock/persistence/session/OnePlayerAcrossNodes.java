package com.uxplima.uxmskyblock.persistence.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryMutationOutcome;
import com.uxplima.uxmskyblock.core.domain.session.PlayerSessionRecord;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.session.SessionAuthorityOutcome;
import com.uxplima.uxmskyblock.persistence.inventory.PlayerProfileInventoryAdapter;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;

/**
 * One player whose session moves between server nodes, on a file database every node shares.
 *
 * <p>Each node is only a name here: what it may do is decided by the session row, which is the point
 * of every test that uses this. A lease running out is written straight into the row, which is what
 * the clock would do given the time.
 */
final class OnePlayerAcrossNodes implements AutoCloseable {

    static final ServerNodeId NODE_A = ServerNodeId.of("node-a");
    static final ServerNodeId NODE_B = ServerNodeId.of("node-b");
    static final ServerNodeId NODE_C = ServerNodeId.of("node-c");

    final PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
    final ProfileId profile = ProfileId.of(UUID.randomUUID());
    final Database database;
    final PlayerSessionAuthorityAdapter sessions;
    final PlayerProfileInventoryAdapter inventories;

    OnePlayerAcrossNodes(Path dir) {
        database = DatabaseTestFixture.createSqliteFile(dir.resolve("sessions.db"));
        new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
        sessions = new PlayerSessionAuthorityAdapter(database);
        inventories = new PlayerProfileInventoryAdapter(database);
    }

    /** The player logs in on {@code node}; the epoch it holds, or a failure when it was refused. */
    SessionAuthorityOutcome login(ServerNodeId node) {
        return sessions.ensureSession(player, profile, node);
    }

    /** The epoch of a login that must have gone through. */
    long loggedIn(ServerNodeId node) {
        SessionAuthorityOutcome outcome = login(node);
        assertThat(outcome.isSuccess())
                .describedAs("%s logs the player in", node)
                .isTrue();
        return ((SessionAuthorityOutcome.Success) outcome).epoch();
    }

    /** {@code node} writes {@code contents} as the inventory, under the version it last read. */
    ProfileInventoryMutationOutcome flush(ServerNodeId node, long epoch, long version, String contents) {
        return inventories.checkpointInventory(
                player, profile, node, epoch, version, contents.getBytes(StandardCharsets.UTF_8));
    }

    /** A flush that must have gone through. */
    void flushed(ServerNodeId node, long epoch, String contents) {
        assertThat(flush(node, epoch, version(), contents).isSuccess())
                .describedAs("%s writes the inventory at epoch %s", node, epoch)
                .isTrue();
    }

    long version() {
        return inventories.loadInventory(profile).orElseThrow().version();
    }

    String inventory() {
        return new String(inventories.loadInventory(profile).orElseThrow().inventoryNbt(), StandardCharsets.UTF_8);
    }

    PlayerSessionRecord session() {
        return sessions.findSession(player).orElseThrow();
    }

    /** Every lease on the row has run out, the way it does when its node stops renewing it. */
    void leaseRunsOut() throws Exception {
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement("UPDATE player_sessions SET"
                        + " lease_expires_at = DATETIME('now', '-30 seconds'),"
                        + " handoff_expires_at = CASE WHEN handoff_expires_at IS NULL THEN NULL"
                        + " ELSE DATETIME('now', '-30 seconds') END"
                        + " WHERE player_uuid = ?")) {
            ps.setString(1, player.value().toString());
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
    }

    @Override
    public void close() {
        if (!database.isClosed()) {
            database.close();
        }
    }
}
