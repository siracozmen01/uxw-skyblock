package com.uxplima.uxmskyblock.persistence.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.session.SessionAuthorityOutcome;
import com.uxplima.uxmskyblock.persistence.inventory.PlayerProfileInventoryAdapter;

/**
 * One player on a server database, and a way to stop a node's write halfway through its transaction.
 *
 * <p>A write that validates the session and then writes the inventory is stopped by holding the
 * inventory row from outside: the write has taken the session row by then and waits on the inventory
 * row, which is exactly the moment the tests need to look at. Nothing sleeps; each wait is on a lock
 * the test holds and releases.
 */
final class SessionRowLockScene {

    static final ServerNodeId NODE_A = ServerNodeId.of("node-a");
    static final ServerNodeId NODE_B = ServerNodeId.of("node-b");

    /** How long a call must stay unfinished to count as waiting on a lock rather than slow. */
    private static final long BLOCKED_MILLIS = 500;

    final Database database;
    final PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
    final ProfileId profile = ProfileId.of(UUID.randomUUID());
    final PlayerSessionAuthorityAdapter sessions;
    final PlayerProfileInventoryAdapter inventories;

    SessionRowLockScene(Database database) {
        this.database = database;
        this.sessions = new PlayerSessionAuthorityAdapter(database);
        this.inventories = new PlayerProfileInventoryAdapter(database);
    }

    /** The player logs in on {@code node}; the epoch it holds. */
    long loggedIn(ServerNodeId node) {
        SessionAuthorityOutcome outcome = sessions.ensureSession(player, profile, node);
        assertThat(outcome.isSuccess()).isTrue();
        return ((SessionAuthorityOutcome.Success) outcome).epoch();
    }

    long version() {
        return inventories.loadInventory(profile).orElseThrow().version();
    }

    String inventory() {
        return new String(inventories.loadInventory(profile).orElseThrow().inventoryNbt(), StandardCharsets.UTF_8);
    }

    static byte[] bytes(String contents) {
        return contents.getBytes(StandardCharsets.UTF_8);
    }

    /** Sets the session lease to run out {@code seconds} from now, by the database clock. */
    void leaseEndsIn(int seconds) throws SQLException {
        String later = database.dialect() == Dialect.POSTGRES
                ? "CURRENT_TIMESTAMP + INTERVAL '" + seconds + " seconds'"
                : "CURRENT_TIMESTAMP + INTERVAL " + seconds + " SECOND";
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(
                        "UPDATE player_sessions SET lease_expires_at = " + later + " WHERE player_uuid = ?")) {
            ps.setString(1, player.value().toString());
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
    }

    /**
     * Waits until the database clock is past the lease. The read takes no lock, so it answers while a
     * node holds the session row.
     */
    void awaitLeaseEnded() throws Exception {
        Instant giveUp = Instant.now().plus(Duration.ofSeconds(15));
        while (Instant.now().isBefore(giveUp)) {
            try (Connection conn = database.connection();
                    PreparedStatement ps = conn.prepareStatement("SELECT CASE WHEN lease_expires_at < CURRENT_TIMESTAMP"
                            + " THEN 1 ELSE 0 END FROM player_sessions WHERE player_uuid = ?")) {
                ps.setString(1, player.value().toString());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) == 1) {
                        return;
                    }
                }
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("the lease never ran out");
    }

    /** Holds the player's inventory row in an open transaction until closed. */
    InventoryRowHeld holdInventoryRow() throws SQLException {
        Connection conn = database.connection();
        conn.setAutoCommit(false);
        try (PreparedStatement ps =
                conn.prepareStatement("SELECT profile_id FROM profile_inventories WHERE profile_id = ? FOR UPDATE")) {
            ps.setString(1, profile.value().toString());
            ps.executeQuery().close();
        }
        return new InventoryRowHeld(conn);
    }

    /** Asserts that {@code call} is waiting, not finished. */
    static void stillWaiting(Future<?> call, String what) {
        assertThatThrownBy(() -> call.get(BLOCKED_MILLIS, TimeUnit.MILLISECONDS))
                .describedAs(what)
                .isInstanceOf(TimeoutException.class);
    }

    /** An open transaction holding the inventory row until it is released. */
    record InventoryRowHeld(Connection conn) {
        /** Rolls the transaction back and lets the row go. */
        void release() throws SQLException {
            try {
                conn.rollback();
            } finally {
                conn.close();
            }
        }
    }
}
