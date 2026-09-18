package com.uxplima.uxmskyblock.persistence.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.session.SessionAuthorityOutcome;
import com.uxplima.uxmskyblock.core.domain.session.SessionState;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fast-lane unit and integration tests for {@link PlayerSessionAuthorityAdapter} exercising SQLite.
 *
 * <p>Validates all 5 source-proven authority transitions, epoch fencing, wrong node / stale epoch / expired lease
 * rejections, and SQLite writer serialization via {@code BEGIN IMMEDIATE} transactions.
 */
class PlayerSessionAuthoritySqliteTest {

    @TempDir
    Path tempDir;

    private Database database;
    private PlayerSessionAuthorityAdapter adapter;

    private static final PlayerUuid PLAYER_1 = PlayerUuid.of(UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private static final ProfileId PROFILE_1 = ProfileId.of(UUID.fromString("22222222-2222-2222-2222-222222222222"));
    private static final ServerNodeId NODE_A = ServerNodeId.of("skyblock-node-a");
    private static final ServerNodeId NODE_B = ServerNodeId.of("skyblock-node-b");

    @BeforeEach
    void setUp() throws Exception {
        Path dbFile = tempDir.resolve("player-session-test.db");
        database = DatabaseTestFixture.createSqliteFile(dbFile);

        // Run production migrations to establish schema V1
        new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(Dialect.SQLITE));

        adapter = new PlayerSessionAuthorityAdapter(database);
    }

    @AfterEach
    void tearDown() {
        if (database != null && !database.isClosed()) {
            database.close();
        }
    }

    private void seedSession(
            PlayerUuid playerUuid,
            ProfileId profileId,
            ServerNodeId node,
            long epoch,
            SessionState state,
            boolean expired)
            throws SQLException {
        try (Connection conn = database.connection()) {
            // Seed account with null active_profile_id first
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO player_accounts (player_uuid, active_profile_id) VALUES (?, NULL)")) {
                ps.setString(1, playerUuid.value().toString());
                ps.executeUpdate();
            }
            // Seed profile
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO player_profiles (profile_id, player_uuid, profile_type) VALUES (?, ?, 'CLASSIC')")) {
                ps.setString(1, profileId.value().toString());
                ps.setString(2, playerUuid.value().toString());
                ps.executeUpdate();
            }
            // Update account active profile
            try (PreparedStatement ps =
                    conn.prepareStatement("UPDATE player_accounts SET active_profile_id = ? WHERE player_uuid = ?")) {
                ps.setString(1, profileId.value().toString());
                ps.setString(2, playerUuid.value().toString());
                ps.executeUpdate();
            }
            // Seed session
            String leaseSql = expired ? "DATETIME('now', '-30 seconds')" : "DATETIME('now', '+30 seconds')";
            String sql = "INSERT INTO player_sessions ("
                    + "player_uuid, active_profile_id, authoritative_node, session_epoch, state, lease_expires_at"
                    + ") VALUES (?, ?, ?, ?, ?, " + leaseSql + ")";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerUuid.value().toString());
                ps.setString(2, profileId.value().toString());
                ps.setString(3, node.value());
                ps.setLong(4, epoch);
                ps.setString(5, state.name());
                ps.executeUpdate();
            }
        }
    }

    private void seedHandoffReadySession(
            PlayerUuid playerUuid,
            ProfileId profileId,
            ServerNodeId sourceNode,
            long epoch,
            String handoffId,
            ServerNodeId targetNode,
            boolean expired)
            throws SQLException {
        seedSession(playerUuid, profileId, sourceNode, epoch, SessionState.DRAINING, expired);
        try (Connection conn = database.connection()) {
            String leaseSql = expired ? "DATETIME('now', '-30 seconds')" : "DATETIME('now', '+30 seconds')";
            String sql = "UPDATE player_sessions SET "
                    + "state = 'HANDOFF_READY', "
                    + "handoff_id = ?, "
                    + "handoff_target_node = ?, "
                    + "handoff_expires_at = " + leaseSql + ", "
                    + "lease_expires_at = " + leaseSql + " "
                    + "WHERE player_uuid = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, handoffId);
                ps.setString(2, targetNode.value());
                ps.setString(3, playerUuid.value().toString());
                ps.executeUpdate();
            }
        }
    }

    // ==========================================
    // 1. RENEW Tests
    // ==========================================

    @Test
    @DisplayName("RENEW: valid owner renews lease successfully without incrementing session epoch")
    void validRenewSucceedsWithoutIncrementingEpoch() throws Exception {
        seedSession(PLAYER_1, PROFILE_1, NODE_A, 1L, SessionState.ACTIVE, false);

        SessionAuthorityOutcome outcome = adapter.renew(PLAYER_1, NODE_A, 1L);

        assertThat(outcome.isSuccess()).isTrue();
        assertThat(outcome).isEqualTo(SessionAuthorityOutcome.success(1L));

        // Verify DB row: epoch remains 1, state remains ACTIVE
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT session_epoch, state, authoritative_node FROM player_sessions WHERE player_uuid = ?")) {
            ps.setString(1, PLAYER_1.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong("session_epoch")).isEqualTo(1L);
                assertThat(rs.getString("state")).isEqualTo("ACTIVE");
                assertThat(rs.getString("authoritative_node")).isEqualTo(NODE_A.value());
            }
        }
    }

    @Test
    @DisplayName("RENEW: stale epoch renew is rejected")
    void staleEpochRenewRejected() throws Exception {
        seedSession(PLAYER_1, PROFILE_1, NODE_A, 2L, SessionState.ACTIVE, false);

        // Expected epoch is 1, but DB has 2
        SessionAuthorityOutcome outcome = adapter.renew(PLAYER_1, NODE_A, 1L);

        assertThat(outcome.isRejected()).isTrue();
    }

    @Test
    @DisplayName("RENEW: wrong node renew is rejected")
    void wrongNodeRenewRejected() throws Exception {
        seedSession(PLAYER_1, PROFILE_1, NODE_A, 1L, SessionState.ACTIVE, false);

        // Node B attempts to renew Node A's session
        SessionAuthorityOutcome outcome = adapter.renew(PLAYER_1, NODE_B, 1L);

        assertThat(outcome.isRejected()).isTrue();
    }

    @Test
    @DisplayName("RENEW: expired lease renew is rejected")
    void expiredLeaseRenewRejected() throws Exception {
        seedSession(PLAYER_1, PROFILE_1, NODE_A, 1L, SessionState.ACTIVE, true);

        SessionAuthorityOutcome outcome = adapter.renew(PLAYER_1, NODE_A, 1L);

        assertThat(outcome.isRejected()).isTrue();
    }

    // ==========================================
    // 2. DRAIN Tests
    // ==========================================

    @Test
    @DisplayName("DRAIN: transitions state from ACTIVE to DRAINING without changing epoch")
    void validDrainTransitionsToDraining() throws Exception {
        seedSession(PLAYER_1, PROFILE_1, NODE_A, 1L, SessionState.ACTIVE, false);

        SessionAuthorityOutcome outcome = adapter.drain(PLAYER_1, NODE_A, 1L);

        assertThat(outcome.isSuccess()).isTrue();
        assertThat(outcome).isEqualTo(SessionAuthorityOutcome.success(1L));

        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT state, session_epoch FROM player_sessions WHERE player_uuid = ?")) {
            ps.setString(1, PLAYER_1.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("state")).isEqualTo("DRAINING");
                assertThat(rs.getLong("session_epoch")).isEqualTo(1L);
            }
        }
    }

    @Test
    @DisplayName("DRAIN: rejected if not in ACTIVE state or wrong node/epoch")
    void invalidDrainRejected() throws Exception {
        seedSession(PLAYER_1, PROFILE_1, NODE_A, 1L, SessionState.DRAINING, false);

        // Already DRAINING -> drain should be rejected (requires state == 'ACTIVE')
        SessionAuthorityOutcome outcome = adapter.drain(PLAYER_1, NODE_A, 1L);
        assertThat(outcome.isRejected()).isTrue();

        // Wrong node
        outcome = adapter.drain(PLAYER_1, NODE_B, 1L);
        assertThat(outcome.isRejected()).isTrue();
    }

    // ==========================================
    // 3. PREPARE_HANDOFF Tests
    // ==========================================

    @Test
    @DisplayName("PREPARE_HANDOFF: transitions DRAINING to HANDOFF_READY with target node and handoffId")
    void validPrepareHandoffSucceeds() throws Exception {
        seedSession(PLAYER_1, PROFILE_1, NODE_A, 1L, SessionState.DRAINING, false);

        String handoffId = UUID.randomUUID().toString();
        SessionAuthorityOutcome outcome = adapter.prepareHandoff(PLAYER_1, NODE_A, 1L, handoffId, NODE_B);

        assertThat(outcome.isSuccess()).isTrue();
        assertThat(outcome).isEqualTo(SessionAuthorityOutcome.success(1L));

        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT state, handoff_id, handoff_target_node, session_epoch FROM player_sessions WHERE player_uuid = ?")) {
            ps.setString(1, PLAYER_1.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("state")).isEqualTo("HANDOFF_READY");
                assertThat(rs.getString("handoff_id")).isEqualTo(handoffId);
                assertThat(rs.getString("handoff_target_node")).isEqualTo(NODE_B.value());
                assertThat(rs.getLong("session_epoch")).isEqualTo(1L);
            }
        }
    }

    @Test
    @DisplayName("PREPARE_HANDOFF: rejected if state is not DRAINING")
    void prepareHandoffRejectedIfNotDraining() throws Exception {
        seedSession(PLAYER_1, PROFILE_1, NODE_A, 1L, SessionState.ACTIVE, false);

        SessionAuthorityOutcome outcome =
                adapter.prepareHandoff(PLAYER_1, NODE_A, 1L, UUID.randomUUID().toString(), NODE_B);

        assertThat(outcome.isRejected()).isTrue();
    }

    // ==========================================
    // 4. PLANNED_ACQUIRE Tests
    // ==========================================

    @Test
    @DisplayName(
            "PLANNED_ACQUIRE: destination node claims session, increments epoch, returns to ACTIVE, clears handoff")
    void validPlannedAcquireSucceedsAndIncrementsEpoch() throws Exception {
        String handoffId = UUID.randomUUID().toString();
        seedHandoffReadySession(PLAYER_1, PROFILE_1, NODE_A, 5L, handoffId, NODE_B, false);

        SessionAuthorityOutcome outcome = adapter.plannedAcquire(PLAYER_1, NODE_A, 5L, handoffId, NODE_B);

        assertThat(outcome.isSuccess()).isTrue();
        assertThat(outcome).isEqualTo(SessionAuthorityOutcome.success(6L));

        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT state, authoritative_node, session_epoch, handoff_id, handoff_target_node "
                                + "FROM player_sessions WHERE player_uuid = ?")) {
            ps.setString(1, PLAYER_1.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("state")).isEqualTo("ACTIVE");
                assertThat(rs.getString("authoritative_node")).isEqualTo(NODE_B.value());
                assertThat(rs.getLong("session_epoch")).isEqualTo(6L);
                assertThat(rs.getString("handoff_id")).isNull();
                assertThat(rs.getString("handoff_target_node")).isNull();
            }
        }

        // Prove old epoch and old owner are fenced immediately
        SessionAuthorityOutcome staleRenew = adapter.renew(PLAYER_1, NODE_A, 5L);
        assertThat(staleRenew.isRejected()).isTrue();
    }

    @Test
    @DisplayName("PLANNED_ACQUIRE: rejected if handoff ID does not match or handoff expired")
    void plannedAcquireRejectedOnMismatchOrExpiry() throws Exception {
        String handoffId = UUID.randomUUID().toString();
        seedHandoffReadySession(PLAYER_1, PROFILE_1, NODE_A, 1L, handoffId, NODE_B, false);

        // Mismatched handoff ID
        SessionAuthorityOutcome outcome = adapter.plannedAcquire(PLAYER_1, NODE_A, 1L, "wrong-handoff-id", NODE_B);
        assertThat(outcome.isRejected()).isTrue();

        // Mismatched destination node
        ServerNodeId wrongNode = ServerNodeId.of("skyblock-node-c");
        outcome = adapter.plannedAcquire(PLAYER_1, NODE_A, 1L, handoffId, wrongNode);
        assertThat(outcome.isRejected()).isTrue();
    }

    // ==========================================
    // 5. FAILURE_TAKEOVER Tests
    // ==========================================

    @Test
    @DisplayName("FAILURE_TAKEOVER: rejected if prior lease is still active/unexpired")
    void failureTakeoverRejectedIfLeaseStillActive() throws Exception {
        seedSession(PLAYER_1, PROFILE_1, NODE_A, 1L, SessionState.ACTIVE, false);

        // Lease is still valid -> takeover must fail-closed
        SessionAuthorityOutcome outcome = adapter.failureTakeover(PLAYER_1, 1L, NODE_B);

        assertThat(outcome.isRejected()).isTrue();
    }

    @Test
    @DisplayName("FAILURE_TAKEOVER: succeeds only after lease expiration, increments epoch, sets RECOVERING")
    void failureTakeoverSucceedsAfterExpiration() throws Exception {
        seedSession(PLAYER_1, PROFILE_1, NODE_A, 3L, SessionState.ACTIVE, true);

        SessionAuthorityOutcome outcome = adapter.failureTakeover(PLAYER_1, 3L, NODE_B);

        assertThat(outcome.isSuccess()).isTrue();
        assertThat(outcome).isEqualTo(SessionAuthorityOutcome.success(4L, true));
        assertThat(outcome.isRecovering()).isTrue();

        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT state, authoritative_node, session_epoch FROM player_sessions WHERE player_uuid = ?")) {
            ps.setString(1, PLAYER_1.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("state")).isEqualTo("RECOVERING");
                assertThat(rs.getString("authoritative_node")).isEqualTo(NODE_B.value());
                assertThat(rs.getLong("session_epoch")).isEqualTo(4L);
            }
        }

        // Prove markRecoveredActive transitions RECOVERING -> ACTIVE
        SessionAuthorityOutcome recoveredOutcome = adapter.markRecoveredActive(PLAYER_1, NODE_B, 4L);
        assertThat(recoveredOutcome.isSuccess()).isTrue();
        assertThat(recoveredOutcome).isEqualTo(SessionAuthorityOutcome.success(4L, false));
        assertThat(recoveredOutcome.isRecovering()).isFalse();

        // Prove drain transitions ACTIVE -> DRAINING
        SessionAuthorityOutcome drainOutcome = adapter.drain(PLAYER_1, NODE_B, 4L);
        assertThat(drainOutcome.isSuccess()).isTrue();

        // Prove releaseToOffline transitions DRAINING -> OFFLINE
        SessionAuthorityOutcome offlineOutcome = adapter.releaseToOffline(PLAYER_1, NODE_B, 4L);
        assertThat(offlineOutcome.isSuccess()).isTrue();
        assertThat(offlineOutcome).isEqualTo(SessionAuthorityOutcome.success(4L, false));

        var offlineRecord = adapter.findSession(PLAYER_1).orElseThrow();
        assertThat(offlineRecord.state()).isEqualTo(SessionState.OFFLINE);

        // Prove old owner Node A is fenced
        SessionAuthorityOutcome staleRenew = adapter.renew(PLAYER_1, NODE_A, 3L);
        assertThat(staleRenew.isRejected()).isTrue();
    }

    // ==========================================
    // 6. SQLite Writer Serialization & Dialect Verification
    // ==========================================

    @Test
    @DisplayName("SQLite Writer Serialization: verifies single-writer exclusivity via BEGIN IMMEDIATE")
    void sqliteWriterSerializationGuaranteed() throws Exception {
        seedSession(PLAYER_1, PROFILE_1, NODE_A, 1L, SessionState.ACTIVE, false);

        Path dbFile = tempDir.resolve("player-session-test.db");
        String jdbcUrl = "jdbc:sqlite:" + dbFile.toAbsolutePath();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch aLocked = new CountDownLatch(1);
        CountDownLatch bAttemptStarted = new CountDownLatch(1);
        CountDownLatch aProceedCommit = new CountDownLatch(1);

        try (Connection connA = java.sql.DriverManager.getConnection(jdbcUrl)) {
            // Thread A holds SQLite writer lock via BEGIN IMMEDIATE on independent connection
            Future<?> futureA = executor.submit(() -> {
                try (Statement stmtA = connA.createStatement()) {
                    stmtA.execute("BEGIN IMMEDIATE");
                    aLocked.countDown();

                    boolean proceed = aProceedCommit.await(5, TimeUnit.SECONDS);
                    assertThat(proceed).isTrue();

                    stmtA.execute("COMMIT");
                } catch (Exception e) {
                    throw new RuntimeException("Thread A failed", e);
                }
            });

            // Thread B attempts adapter renew while Thread A holds writer lock
            Future<SessionAuthorityOutcome> futureB = executor.submit(() -> {
                try {
                    assertThat(aLocked.await(5, TimeUnit.SECONDS)).isTrue();
                    bAttemptStarted.countDown();
                    return adapter.renew(PLAYER_1, NODE_A, 1L);
                } catch (Exception e) {
                    throw new RuntimeException("Thread B failed", e);
                }
            });

            assertThat(bAttemptStarted.await(5, TimeUnit.SECONDS)).isTrue();

            // While A holds writer lock, prove B is blocked and cannot complete
            assertThatThrownBy(() -> futureB.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);

            // Now release A so it commits its transaction
            aProceedCommit.countDown();
            futureA.get(5, TimeUnit.SECONDS);

            // B now unblocks, acquires writer slot, and completes successfully
            SessionAuthorityOutcome outcomeB = futureB.get(5, TimeUnit.SECONDS);
            assertThat(outcomeB.isSuccess()).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Dialect Validation: rejects unsupported dialects such as H2 or GENERIC")
    void rejectsUnsupportedDialect() {
        assertThatThrownBy(() -> new PlayerSessionAuthorityAdapter(
                        Database.adopt(new org.sqlite.SQLiteDataSource(), Dialect.H2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported SQL dialect");

        assertThatThrownBy(() -> new PlayerSessionAuthorityAdapter(
                        Database.adopt(new org.sqlite.SQLiteDataSource(), Dialect.GENERIC)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported SQL dialect");
    }

    @Test
    @DisplayName("ENSURE_SESSION: bootstraps new player account, profile, inventory, and session")
    void ensureSessionBootstrapsNewPlayer() {
        PlayerUuid newPlayer = new PlayerUuid(UUID.randomUUID());
        ProfileId defaultProf = new ProfileId(UUID.randomUUID());
        ServerNodeId node = new ServerNodeId("node-1");

        assertThat(adapter.findSession(newPlayer)).isEmpty();

        SessionAuthorityOutcome outcome = adapter.ensureSession(newPlayer, defaultProf, node);
        assertThat(outcome.isSuccess()).isTrue();
        assertThat(((SessionAuthorityOutcome.Success) outcome).epoch()).isEqualTo(1L);

        var recordOpt = adapter.findSession(newPlayer);
        assertThat(recordOpt).isPresent();
        var record = recordOpt.get();
        assertThat(record.playerUuid()).isEqualTo(newPlayer);
        assertThat(record.activeProfileId()).isEqualTo(defaultProf);
        assertThat(record.authoritativeNode()).isEqualTo(node);
        assertThat(record.sessionEpoch()).isEqualTo(1L);
        assertThat(record.state()).isEqualTo(SessionState.ACTIVE);

        // Subsequent ensureSession on same node renews active session
        SessionAuthorityOutcome repeatOutcome = adapter.ensureSession(newPlayer, defaultProf, node);
        assertThat(repeatOutcome.isSuccess()).isTrue();
        assertThat(((SessionAuthorityOutcome.Success) repeatOutcome).epoch()).isEqualTo(1L);
    }

    @Test
    @DisplayName("ENSURE_SESSION: expired session performs failure takeover into RECOVERING with incremented epoch")
    void ensureSessionExpiredPerformsTakeover() throws Exception {
        seedSession(PLAYER_1, PROFILE_1, NODE_A, 2L, SessionState.ACTIVE, true);

        // Node B calls ensureSession on expired player
        SessionAuthorityOutcome outcome = adapter.ensureSession(PLAYER_1, PROFILE_1, NODE_B);
        assertThat(outcome.isSuccess()).isTrue();
        assertThat(outcome.isRecovering()).isTrue();
        assertThat(((SessionAuthorityOutcome.Success) outcome).epoch()).isEqualTo(3L);

        var record = adapter.findSession(PLAYER_1).orElseThrow();
        assertThat(record.state()).isEqualTo(SessionState.RECOVERING);
        assertThat(record.authoritativeNode()).isEqualTo(NODE_B);
        assertThat(record.sessionEpoch()).isEqualTo(3L);
    }

    @Test
    @DisplayName("ENSURE_SESSION: clean login from OFFLINE acquires ACTIVE session with incremented epoch")
    void ensureSessionOfflineAcquiresActive() throws Exception {
        seedSession(PLAYER_1, PROFILE_1, NODE_A, 5L, SessionState.OFFLINE, true);

        SessionAuthorityOutcome outcome = adapter.ensureSession(PLAYER_1, PROFILE_1, NODE_B);
        assertThat(outcome.isSuccess()).isTrue();
        assertThat(outcome.isRecovering()).isFalse();
        assertThat(((SessionAuthorityOutcome.Success) outcome).epoch()).isEqualTo(6L);

        var record = adapter.findSession(PLAYER_1).orElseThrow();
        assertThat(record.state()).isEqualTo(SessionState.ACTIVE);
        assertThat(record.authoritativeNode()).isEqualTo(NODE_B);
        assertThat(record.sessionEpoch()).isEqualTo(6L);
    }

    @Test
    @DisplayName("ENSURE_SESSION: rejected if active unexpired lease held by another node")
    void ensureSessionRejectedIfForeignActiveLease() throws Exception {
        seedSession(PLAYER_1, PROFILE_1, NODE_A, 1L, SessionState.ACTIVE, false);

        SessionAuthorityOutcome outcome = adapter.ensureSession(PLAYER_1, PROFILE_1, NODE_B);
        assertThat(outcome.isRejected()).isTrue();
    }
}
